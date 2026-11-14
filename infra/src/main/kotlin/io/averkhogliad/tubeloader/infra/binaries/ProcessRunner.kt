package io.averkhogliad.tubeloader.infra.binaries

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.lang.Process
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything a start of an external tool consists of: the command, where it runs, which environment
 * it sees and how long it may take. A null environment inherits the environment of the application;
 * a null timeout waits for the tool without a budget.
 */
data class ProcessLaunch(
    val command: List<String>,
    val workingDirectory: Path? = null,
    val environment: Map<String, String>? = null,
    val timeout: Duration? = null,
)

/** What a started tool ended with: the exit code and its pipes, a timeout, or a start that never happened. */
sealed interface ProcessOutcome {

    data class Exited(
        val exitCode: Int,
        val output: List<String>,
        val errorText: String,
    ) : ProcessOutcome

    data object TimedOut : ProcessOutcome

    data class SpawnFailed(val cause: IOException) : ProcessOutcome
}

/**
 * Starts an external CLI tool and waits for it to finish. The mechanism owns the pipes and the
 * process tree; what the outcome means is the consumer's business: a non-zero code is not a failure
 * here, and neither is a line of the error stream.
 *
 * [onOutputLine] receives each line of the output stream as it comes; the outcome carries the head
 * of both pipes, bounded by the limits of the runner.
 */
interface ProcessRunner {

    suspend fun run(launch: ProcessLaunch, onOutputLine: (String) -> Unit = {}): ProcessOutcome
}

/**
 * Starts the tool as its own process. Cancelling the coroutine of the call kills the whole process
 * tree: no orphaned child is left behind. A timeout is an outcome, not a cancellation: the tree is
 * killed and the call returns.
 *
 * The process boundary is a seam: tests hand in a process of their own, so the control flow of the
 * runner is observable without starting anything.
 */
class SystemProcessRunner internal constructor(
    private val start: (ProcessLaunch) -> Process = ProcessLaunch::toProcess,
    private val graceTimeout: Duration = DEFAULT_GRACE_TIMEOUT,
    private val outputLimitLines: Int = OUTPUT_LIMIT_LINES,
    private val errorLimitChars: Int = ERROR_LIMIT_CHARS,
) : ProcessRunner {

    /** The composition root starts the tool as it is; the process seam of a test stays internal. */
    constructor() : this(ProcessLaunch::toProcess)

    override suspend fun run(launch: ProcessLaunch, onOutputLine: (String) -> Unit): ProcessOutcome =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    start(launch)
                } catch (failure: IOException) {
                    return@withContext ProcessOutcome.SpawnFailed(failure)
                }
            try {
                val output = mutableListOf<String>()
                val errorText = StringBuilder()
                val exitCode =
                    coroutineScope {
                        val outputReader = async { process.inputStream.streamLines(output, onOutputLine) }
                        val errorReader = async { process.errorStream.collectInto(errorText) }
                        val exited = awaitExit(process, launch.timeout)
                        if (exited == null) stopTree(process)
                        outputReader.await()
                        errorReader.await()
                        exited?.exitValue()
                    }
                if (exitCode == null) {
                    ProcessOutcome.TimedOut
                } else {
                    ProcessOutcome.Exited(exitCode, output, errorText.toString())
                }
            } catch (cancellation: CancellationException) {
                stopTree(process)
                coroutineContext.ensureActive()
                throw cancellation
            }
        }

    private suspend fun awaitExit(process: Process, timeout: Duration?): Process? =
        if (timeout == null) {
            process.onExit().await()
        } else {
            withTimeoutOrNull(timeout) { process.onExit().await() }
        }

    private suspend fun stopTree(process: Process) {
        withContext(NonCancellable) {
            process.descendants().forEach { child -> child.destroy() }
            process.destroy()
            val exited =
                withTimeoutOrNull(graceTimeout) {
                    process.onExit().await()
                }
            if (exited == null) process.destroyForcibly()
        }
    }

    private suspend fun InputStream.streamLines(output: MutableList<String>, onOutputLine: (String) -> Unit) {
        bufferedReader().use { reader ->
            while (true) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                if (output.size < outputLimitLines) output += line
                onOutputLine(line)
            }
        }
    }

    private suspend fun InputStream.collectInto(sink: StringBuilder) {
        bufferedReader().use { reader ->
            while (true) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                if (sink.length < errorLimitChars) sink.appendLine(line)
            }
        }
    }

    internal companion object {

        val DEFAULT_GRACE_TIMEOUT = 2.seconds
        const val OUTPUT_LIMIT_LINES = 10_000
        const val ERROR_LIMIT_CHARS = 16_384
    }
}

/** Builds the process of a launch: the command, the directory it runs from and the environment it sees. */
internal fun ProcessLaunch.toProcess(): Process {
    val builder = ProcessBuilder(command)
    workingDirectory?.let { directory -> builder.directory(directory.toFile()) }
    environment?.let { variables ->
        builder.environment().clear()
        builder.environment().putAll(variables)
    }
    return builder.start()
}
