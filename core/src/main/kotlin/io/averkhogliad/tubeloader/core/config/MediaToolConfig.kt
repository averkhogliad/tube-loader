package io.averkhogliad.tubeloader.core.config

import java.nio.file.Path

/**
 * Where the media tool keeps its artifacts, which binary to run, and which container the output
 * carries. The path of the home directory is owned by the composition root, shared with
 * [io.averkhogliad.tubeloader.config.defaultSources].
 *
 * [binaryPath] overrides the binary the implementation would otherwise place under [homeDir]; set it
 * where the tool is already installed (CI, tests).
 *
 * [containerFormat] is passed explicitly: the tool picks a container by the last extension of the
 * output name, and a staging name hides it.
 */
data class MediaToolConfig(
    val homeDir: Path,
    val binaryPath: Path? = null,
    val containerFormat: String = DEFAULT_CONTAINER_FORMAT,
) {

    companion object {

        const val DEFAULT_CONTAINER_FORMAT = "mp4"
    }
}
