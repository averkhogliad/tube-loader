package io.averkhogliad.tubeloader.core.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.lang.Process
import kotlin.coroutines.coroutineContext

/**
 * Starts the command as an external process and waits for its exit. An exit code other than zero is
 * reported as a failure carrying what the tool wrote to its error stream.
 *
 * Cancelling the coroutine of the call kills the process tree, then waits briefly for a graceful exit
 * before forcing it: the port promises that no orphaned child survives the operation.
 *
 * The process boundary is a seam: tests hand in a process of their own, so the control flow of the
 * runner is observable without starting anything.
 */
internal class FfmpegProcessRunner(
    private val start: (List<String>) -> Process = { command -> ProcessBuilder(command).start() },
    private val graceTimeoutMillis: Long = GRACE_TIMEOUT_MILLIS,
) : ProcessRunner {

    override suspend fun run(command: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    start(command)
                } catch (failure: IOException) {
                    return@withContext Result.failure(failure)
                }

            try {
                val exitCode = process.onExit().await().exitValue()
                if (exitCode == 0) Result.success(Unit) else Result.failure(ToolFailed(exitCode, errorText(process)))
            } catch (cancellation: CancellationException) {
                stopTree(process)
                coroutineContext.ensureActive()
                throw cancellation
            }
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

    private fun errorText(process: Process): String =
        runCatching {
            process.errorStream
                .readAllBytes()
                .decodeToString()
                .trim()
        }.getOrDefault("")

    internal companion object {
        const val GRACE_TIMEOUT_MILLIS = 2_000L
    }
}

/** The tool answered with a non-zero code: the operation fails, the reason is what the tool said. */
internal class ToolFailed(exitCode: Int, toolMessage: String) :
    IOException(
        if (toolMessage.isEmpty()) {
            "the tool exited with code $exitCode"
        } else {
            "the tool exited with code $exitCode: $toolMessage"
        },
    )
