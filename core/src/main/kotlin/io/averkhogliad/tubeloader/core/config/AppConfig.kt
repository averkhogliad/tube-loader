package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.ConfigReader
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class AppConfig(
    val maxParallelDownloads: Int = DEFAULT_MAX_PARALLEL_DOWNLOADS,
    val defaultTargetDir: Path? = null,
    val httpTool: HttpToolConfig,
) {

    init {
        require(maxParallelDownloads >= 1) {
            "maxParallelDownloads must be >= 1, got $maxParallelDownloads"
        }
    }

    companion object {

        const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 3
        const val DEFAULT_KEY_PREFIX = "download"

        /**
         * Reads the settings through the source-agnostic reader.
         *
         * `max-parallel-downloads` and `default-target-dir` keep the lenient reading they had before
         * the typed reader: an unreadable value falls back to the default instead of failing, while
         * the http tool block is read strictly.
         */
        fun fromReader(reader: ConfigReader, keyPrefix: String = DEFAULT_KEY_PREFIX): AppConfig =
            AppConfig(
                maxParallelDownloads = maxParallelDownloads(reader, keyPrefix),
                defaultTargetDir = defaultTargetDir(reader, keyPrefix),
                httpTool =
                    HttpToolConfig.fromReader(
                        reader,
                        if (keyPrefix == DEFAULT_KEY_PREFIX) HttpToolConfig.DEFAULT_TABLE else "$keyPrefix.http-tool",
                    ),
            )

        private fun maxParallelDownloads(reader: ConfigReader, keyPrefix: String): Int {
            val parsed =
                reader
                    .text("$keyPrefix.max-parallel-downloads")
                    ?.trim()
                    ?.toIntOrNull()
            return parsed?.takeIf { it >= 1 }
                ?: DEFAULT_MAX_PARALLEL_DOWNLOADS
        }

        private fun defaultTargetDir(reader: ConfigReader, keyPrefix: String): Path? =
            reader
                .text("$keyPrefix.default-target-dir")
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
