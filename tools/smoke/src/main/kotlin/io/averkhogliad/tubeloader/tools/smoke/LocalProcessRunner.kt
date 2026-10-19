package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.media.ProcessRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [io.averkhogliad.tubeloader.core.media.FfmpegProcessRunner] from the smoke script:
 * starts the command, waits for it, and reports a non-zero exit as an [IOException]. Cancelling the
 * coroutine kills the process tree.
 *
 * Lives here because the production implementation is `internal` to `:core` — a smoke script
 * cannot depend on it. The logic is intentionally narrow: this is a one-binary tool runner, not a
 * general process abstraction.
 */
internal class LocalProcessRunner(private val graceTimeout: Duration = 2.seconds) : ProcessRunner {

    override suspend fun run(command: List<String>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder(command).start()
                } catch (failure: IOException) {
                    return@withContext Result.failure(failure)
                }

            try {
                val exitCode = process.onExit().await().exitValue()
                if (exitCode == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("the tool exited with code $exitCode: ${errorText(process)}"))
                }
            } catch (cancellation: CancellationException) {
                stopTree(process)
                coroutineContext.ensureActive()
                throw cancellation
            }
        }
    }

    private suspend fun stopTree(process: Process) {
        withContext(NonCancellable) {
            process
                .descendants()
                .forEach { child -> child.destroy() }
            process.destroy()
            withTimeoutOrNull(graceTimeout) { process.onExit().await() }
                ?: process.destroyForcibly()
        }
    }

    private fun errorText(process: Process): String =
        runCatching {
            process.errorStream
                .readAllBytes()
                .decodeToString()
                .trim()
        }.getOrDefault("")
}
