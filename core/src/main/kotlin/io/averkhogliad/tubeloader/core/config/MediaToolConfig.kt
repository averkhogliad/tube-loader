package io.averkhogliad.tubeloader.core.config

import java.nio.file.Path

/**
 * Where the media tool keeps its artifacts, which binary to run, and which container the output
 * carries. The paths come from [ToolPaths], resolved at the composition root and shared with the
 * other tools of the application; nothing here derives them.
 *
 * [binaryPath] overrides the binary [ToolPaths.ffmpegBinary] names; set it where the tool is already
 * installed (CI, tests).
 *
 * [containerFormat] is passed explicitly: the tool picks a container by the last extension of the
 * output name, and a staging name hides it.
 */
data class MediaToolConfig(
    val paths: ToolPaths,
    val binaryPath: Path? = null,
    val containerFormat: String = DEFAULT_CONTAINER_FORMAT,
) {

    companion object {

        const val DEFAULT_CONTAINER_FORMAT = "mp4"
    }
}
