package io.averkhogliad.tubeloader.core.config

import java.nio.file.Path

/**
 * The paths of the external tools, resolved once where the object graph is built. Both consumers —
 * the media tool and the yt-dlp adapter — take them from here instead of deriving the layout of the
 * other: a tool is found only where this component points at it.
 *
 * [homeDir] is the directory of the user data of the application.
 * [ffmpegBinary] is the exact file to run.
 * [ytDlpBinary] is the exact file to run of the delegate adapter.
 */
data class ToolPaths(
    val homeDir: Path,
    val ffmpegBinary: Path,
    val ytDlpBinary: Path,
) {

    companion object {

        /** The directory the managed build of the tool is kept in, under the home directory. */
        const val FFMPEG_DIR = "ffmpeg"

        /** The directory the managed build of the delegate tool is kept in, under the home directory. */
        const val YT_DLP_DIR = "yt-dlp"

        /** The only platform difference the layout makes: how the executable of the tool is named. */
        fun executableName(osName: String = System.getProperty("os.name")): String =
            if (osName.lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"

        /** The executable of the delegate tool: the managed build of its source is a bare file. */
        fun ytDlpExecutableName(osName: String = System.getProperty("os.name")): String =
            if (osName.lowercase().contains("win")) "yt-dlp.exe" else "yt-dlp"

        fun of(homeDir: Path, osName: String = System.getProperty("os.name")): ToolPaths =
            ToolPaths(
                homeDir = homeDir,
                ffmpegBinary = homeDir.resolve(FFMPEG_DIR).resolve(executableName(osName)),
                ytDlpBinary = homeDir.resolve(YT_DLP_DIR).resolve(ytDlpExecutableName(osName)),
            )
    }
}
