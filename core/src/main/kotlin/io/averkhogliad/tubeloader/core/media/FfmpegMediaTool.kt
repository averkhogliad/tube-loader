package io.averkhogliad.tubeloader.core.media

import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.port.MediaTool
import java.nio.file.Path

/**
 * Starts the tool, one process per operation, and waits for it to finish. Cancelling the coroutine
 * of the operation kills the whole process tree: no orphaned child is left behind.
 */
interface ProcessRunner {
    suspend fun run(command: List<String>): Result<Unit>
}

/**
 * The tool runs as an external `ffmpeg` process, one per operation; nothing outlives the call, so
 * closing has nothing to release.
 *
 * The binary is not shipped with the application: the first operation fetches the official static
 * build into the ffmpeg directory of the home directory, later ones reuse the file already there.
 */
class FfmpegMediaTool(private val config: MediaToolConfig, private val runner: ProcessRunner) : MediaTool {

    private val distribution = FfmpegDistribution(config.homeDir)

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
        return runner.run(command).onFailure { failure -> log("the tool failed: ${failure.message}") }
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
        const val LOG_PREFIX = "FfmpegMediaTool:"
    }
}
