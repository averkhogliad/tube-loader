package io.averkhogliad.tubeloader.adapters.youtube

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

private const val NO_CONFIG = "--no-config"
private const val ERROR_MARK = "ERROR:"
private const val PYTHON_UTF8 = "PYTHONUTF8"
private const val PYTHON_UTF8_ON = "1"
private const val OUTPUT_LIMIT_LINES = 10_000
private const val ERROR_LIMIT_CHARS = 16_384

private val INHERITED_VARIABLES =
    setOf(
        "PATH",
        "PATHEXT",
        "COMSPEC",
        "SYSTEMROOT",
        "WINDIR",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "HOME",
        "LOCALAPPDATA",
        "APPDATA",
        "LANG",
        "LC_ALL",
    )

/** The tool takes its options only from the command line: a config left in the working directory is never read. */
internal fun withNoConfig(command: List<String>): List<String> {
    require(command.isNotEmpty()) { "the command names no binary" }
    return listOf(command.first(), NO_CONFIG) + command.drop(1).filterNot { option -> option == NO_CONFIG }
}

/**
 * The environment the tool runs in: nothing Python related is inherited, the text encoding is forced,
 * and the rest is what the process needs to start on its own platform.
 */
internal fun ytDlpEnvironment(inherited: Map<String, String>): Map<String, String> {
    val allowed = inherited.filterKeys { name -> INHERITED_VARIABLES.any { it.equals(name, ignoreCase = true) } }
    return allowed + (PYTHON_UTF8 to PYTHON_UTF8_ON)
}

/** Everything a start of the tool consists of; the process boundary is a seam, tests hand in a process of their own. */
internal data class YtDlpLaunch(
    val command: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
) {
    fun toProcess(): Process {
        val builder = ProcessBuilder(command).directory(workingDirectory.toFile())
        builder.environment().clear()
        builder.environment().putAll(environment)
        return builder.start()
    }
}

/** What the tool wrote to its output stream, in order. */
internal data class YtDlpRun(val output: List<String>)

/**
 * Starts the tool, streams its output line by line and waits for the exit.
 *
 * The exit code alone decides nothing: the tool names its own failures on the error stream, and an
 * error reported there fails the run even when the code is zero.
 *
 * A timeout is an outcome, not a cancellation: the process tree is killed and the call returns.
 * Cancelling the coroutine of the call kills the tree as well, so no orphaned child survives.
 */
internal class YtDlpProcessRunner(
    private val workingDirectory: Path,
    private val start: (YtDlpLaunch) -> Process = YtDlpLaunch::toProcess,
    private val graceTimeoutMillis: Long = GRACE_TIMEOUT_MILLIS,
) {

    suspend fun run(
        command: List<String>,
        timeout: Duration? = null,
        onOutputLine: (String) -> Unit = {},
    ): Result<YtDlpRun> =
        withContext(Dispatchers.IO) {
            val launch = YtDlpLaunch(withNoConfig(command), workingDirectory, ytDlpEnvironment(System.getenv()))
            val process =
                try {
                    start(launch)
                } catch (failure: IOException) {
                    return@withContext Result.failure(failure)
                }
            try {
                val output = mutableListOf<String>()
                val errorText = StringBuilder()
                val finished =
                    coroutineScope {
                        val outputReader = async { process.inputStream.streamLines(output, onOutputLine) }
                        val errorReader = async { process.errorStream.collectInto(errorText) }
                        val exited = awaitExit(process, timeout)
                        if (exited == null) stopTree(process)
                        outputReader.await()
                        errorReader.await()
                        exited
                    }
                if (finished == null) {
                    Result.failure(YtDlpTimedOut(timeout))
                } else {
                    outcomeOf(finished.exitValue(), output, errorText.toString())
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

    private fun outcomeOf(exitCode: Int, output: List<String>, errorText: String): Result<YtDlpRun> {
        val error = errorText.trim()
        val reported = error.lineSequence().map(String::trim).firstOrNull { line -> line.startsWith(ERROR_MARK) }
        if (exitCode == 0 && reported == null) return Result.success(YtDlpRun(output))
        return Result.failure(YtDlpFailed(exitCode, error))
    }

    private suspend fun stopTree(process: Process) {
        withContext(NonCancellable) {
            process.descendants().forEach { child -> child.destroy() }
            process.destroy()
            val exited =
                withTimeoutOrNull(graceTimeoutMillis) {
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
                if (output.size < OUTPUT_LIMIT_LINES) output += line
                onOutputLine(line)
            }
        }
    }

    private suspend fun InputStream.collectInto(sink: StringBuilder) {
        bufferedReader().use { reader ->
            while (true) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                if (sink.length < ERROR_LIMIT_CHARS) sink.appendLine(line)
            }
        }
    }

    internal companion object {
        const val GRACE_TIMEOUT_MILLIS = 2_000L
    }
}

/** The tool answered with a failure: the reason is what it said on its error stream. */
internal class YtDlpFailed(exitCode: Int, val toolMessage: String) :
    IOException(
        if (toolMessage.isEmpty()) {
            "the tool exited with code $exitCode"
        } else {
            "the tool exited with code $exitCode: $toolMessage"
        },
    )

/** The tool did not finish within the budget of its own call: the tree is already killed. */
internal class YtDlpTimedOut(timeout: Duration?) : IOException("the tool did not finish within $timeout")
