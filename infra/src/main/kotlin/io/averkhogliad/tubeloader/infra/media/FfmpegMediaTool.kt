package io.averkhogliad.tubeloader.infra.media

import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.port.MediaTool
import io.averkhogliad.tubeloader.infra.binaries.ManagedBinary
import io.averkhogliad.tubeloader.infra.binaries.ProcessLaunch
import io.averkhogliad.tubeloader.infra.binaries.ProcessOutcome
import io.averkhogliad.tubeloader.infra.binaries.ProcessRunner
import java.io.IOException
import java.nio.file.Path

/**
 * The tool runs as an external `ffmpeg` process, one per operation; nothing outlives the call, so
 * closing has nothing to release.
 *
 * The binary is not shipped with the application: the composition root hands in the managed
 * distribution, so every operation of the run shares one instance and one fetch. The process
 * mechanism is shared with the other tools of the application; this consumer decides what its
 * outcome means.
 */
class FfmpegMediaTool(
    private val config: MediaToolConfig,
    private val runner: ProcessRunner,
    private val distribution: ManagedBinary,
) : MediaTool {

    override fun close() = Unit

    override suspend fun mux(
        videoTrack: Path,
        audioTrack: Path,
        output: Path,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> =
        execute(
            build = { binary ->
                listOf(
                    binary.toString(),
                    OVERWRITE,
                    INPUT,
                    videoTrack.toString(),
                    INPUT,
                    audioTrack.toString(),
                    CODEC,
                    STREAM_COPY,
                    SHORTEST,
                    LOG_LEVEL,
                    ERROR,
                    HIDE_BANNER,
                    FORMAT,
                    config.containerFormat,
                    output.toString(),
                )
            },
            onProgress = onProgress,
        )

    override suspend fun remux(input: Path, output: Path, onProgress: (Progress) -> Unit): Result<Unit> =
        execute(
            build = { binary ->
                listOf(
                    binary.toString(),
                    OVERWRITE,
                    INPUT,
                    input.toString(),
                    CODEC,
                    STREAM_COPY,
                    LOG_LEVEL,
                    ERROR,
                    HIDE_BANNER,
                    FORMAT,
                    config.containerFormat,
                    output.toString(),
                )
            },
            onProgress = onProgress,
        )

    private suspend fun execute(build: (Path) -> List<String>, onProgress: (Progress) -> Unit): Result<Unit> {
        val binary =
            distribution.ensureBinary(config.binaryPath).getOrElse { failure ->
                log("the tool is not available: ${failure.message}")
                return Result.failure(failure)
            }
        val command = build(binary)
        log(command.joinToString(" "))
        // the duration of a stream copy is unpredictable, so the progress stays indeterminate
        onProgress(Progress.Indeterminate)
        return runner
            .run(ProcessLaunch(command))
            .judgement()
            .onFailure { failure -> log("the tool failed: ${failure.message}") }
    }

    private fun ProcessOutcome.judgement(): Result<Unit> =
        when (this) {
            is ProcessOutcome.Exited -> {
                if (exitCode == 0) Result.success(Unit) else Result.failure(ToolFailed(exitCode, errorText))
            }

            ProcessOutcome.TimedOut -> {
                Result.failure(ToolTimedOut())
            }

            is ProcessOutcome.SpawnFailed -> {
                Result.failure(cause)
            }
        }

    private fun log(message: String) {
        System.err.println("$LOG_PREFIX $message")
    }

    private companion object {
        const val OVERWRITE = "-y"
        const val INPUT = "-i"
        const val CODEC = "-c"
        const val STREAM_COPY = "copy"
        const val SHORTEST = "-shortest"
        const val LOG_LEVEL = "-loglevel"
        const val ERROR = "error"
        const val HIDE_BANNER = "-hide_banner"
        const val FORMAT = "-f"
        const val LOG_PREFIX = "FfmpegMediaTool:"
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

/** The tool did not finish within the budget of its own call: the tree is already killed. */
internal class ToolTimedOut : IOException("the tool did not finish within its budget")
