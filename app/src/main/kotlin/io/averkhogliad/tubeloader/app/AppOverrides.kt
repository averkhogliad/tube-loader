package io.averkhogliad.tubeloader.app

import java.nio.file.Path

/**
 * The settings a frontend sets over the config of the application.
 *
 * A null leaves the value the config named, so the priority is the frontend, then the config, then
 * the default of the core: a frontend that names nothing lets the config file decide.
 */
data class AppOverrides(val maxParallelDownloads: Int? = null, val defaultTargetDir: Path? = null)
