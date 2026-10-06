package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.Config
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class AppConfig(
    val maxParallelDownloads: Int = DEFAULT_MAX_PARALLEL_DOWNLOADS,
    val defaultTargetDir: Path? = null,
) {

    init {
        require(maxParallelDownloads >= 1) {
            "maxParallelDownloads must be >= 1, got $maxParallelDownloads"
        }
    }

    companion object {

        const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 3
        const val DEFAULT_KEY_PREFIX = "download"

        fun fromConfig(config: Config, keyPrefix: String = DEFAULT_KEY_PREFIX): AppConfig =
            AppConfig(
                maxParallelDownloads = maxParallelDownloads(config, keyPrefix),
                defaultTargetDir = defaultTargetDir(config, keyPrefix),
            )

        private fun maxParallelDownloads(config: Config, keyPrefix: String): Int {
            val parsed =
                config
                    .getOrNull("$keyPrefix.max-parallel-downloads")
                    ?.trim()
                    ?.toIntOrNull()
            return parsed?.takeIf { it >= 1 }
                ?: DEFAULT_MAX_PARALLEL_DOWNLOADS
        }

        private fun defaultTargetDir(config: Config, keyPrefix: String): Path? =
            config
                .getOrNull("$keyPrefix.default-target-dir")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { raw ->
                    try {
                        Path.of(raw)
                    } catch (_: InvalidPathException) {
                        null
                    }
                }
    }
}
