package io.averkhogliad.tubeloader.core.media

import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.port.MediaTool
import java.nio.file.Path

/**
 * Runs the tool: the process boundary of the implementation. The composition root provides the
 * runner, so the command the tool would execute is observable without starting a process.
 */
interface ProcessRunner {
    suspend fun run(command: List<String>): Result<Unit>
}

/**
 * The tool runs as an external `ffmpeg` process, one per operation; nothing outlives the call, so
 * closing has nothing to release.
 */
class FfmpegMediaTool(private val config: MediaToolConfig, private val runner: ProcessRunner) : MediaTool {

    override fun close() = Unit

    override suspend fun mux(
        videoTrack: Path,
        audioTrack: Path,
        output: Path,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> =
        runner.run(
            listOf(
                binaryPath().toString(),
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
            ),
        )

    override suspend fun remux(input: Path, output: Path, onProgress: (Progress) -> Unit): Result<Unit> =
        runner.run(
            listOf(
                binaryPath().toString(),
                OVERWRITE,
                INPUT,
                input.toString(),
                CODEC,
                STREAM_COPY,
                LOG_LEVEL,
                ERROR,
                HIDE_BANNER,
                output.toString(),
            ),
        )

    private fun binaryPath(): Path =
        config.binaryPath ?: config.homeDir.resolve(BINARY_DIR).resolve(ffmpegExecutableName())

    private companion object {
        const val OVERWRITE = "-y"
        const val INPUT = "-i"
        const val CODEC = "-c"
        const val STREAM_COPY = "copy"
        const val SHORTEST = "-shortest"
        const val LOG_LEVEL = "-loglevel"
        const val ERROR = "error"
        const val HIDE_BANNER = "-hide_banner"
        const val BINARY_DIR = "ffmpeg"
    }
}

/** The only platform difference the implementation makes: how the executable of the tool is named. */
internal fun ffmpegExecutableName(osName: String = System.getProperty("os.name")): String =
    if (osName.lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
