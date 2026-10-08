package io.averkhogliad.tubeloader.core.config

import java.nio.file.Path

/**
 * Where the media tool keeps its artifacts and which binary to run. The path of the home directory is
 * owned by the composition root, shared with [io.averkhogliad.tubeloader.config.defaultSources].
 *
 * [binaryPath] overrides the binary the implementation would otherwise place under [homeDir]; set it
 * where the tool is already installed (CI, tests).
 */
data class MediaToolConfig(val homeDir: Path, val binaryPath: Path? = null)
