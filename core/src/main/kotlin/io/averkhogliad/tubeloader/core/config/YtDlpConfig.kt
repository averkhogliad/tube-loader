package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.Config
import io.averkhogliad.tubeloader.core.media.ffmpegDirectory
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the yt-dlp adapter needs beyond the protocol: which binary to run, where the bundled ffmpeg
 * sits, how long a resolve may take, and how many fragments a download pulls at once.
 *
 * [binaryPath] overrides the binary the distribution policy would place on the machine; set it where
 * the tool is already installed (CI, tests). A null path leaves the choice to the implementation.
 *
 * [ffmpegLocation] is the directory the media tool keeps its binary in: the download merges video and
 * audio itself and finds ffmpeg only where it is pointed at.
 */
data class YtDlpConfig(
    val ffmpegLocation: Path,
    val binaryPath: Path? = null,
    val resolveTimeout: Duration = DEFAULT_RESOLVE_TIMEOUT,
    val concurrentFragments: Int = DEFAULT_CONCURRENT_FRAGMENTS,
    val retryMaxAttempts: Int = DEFAULT_RETRY_MAX_ATTEMPTS,
    val retryBaseDelay: Duration = DEFAULT_RETRY_BASE_DELAY,
    val retryRandomizationFactor: Double = DEFAULT_RETRY_RANDOMIZATION_FACTOR,
) {

    init {
        require(resolveTimeout > Duration.ZERO) { "resolveTimeout must be positive, got $resolveTimeout" }
        require(concurrentFragments >= 1) {
            "concurrentFragments must be at least 1, got $concurrentFragments"
        }
        require(retryMaxAttempts >= 1) { "retryMaxAttempts must be at least 1, got $retryMaxAttempts" }
        require(retryBaseDelay >= Duration.ZERO) { "retryBaseDelay must not be negative, got $retryBaseDelay" }
        require(retryRandomizationFactor in 0.0..1.0) {
            "retryRandomizationFactor must be within 0.0..1.0, got $retryRandomizationFactor"
        }
    }

    companion object {

        const val DEFAULT_TABLE = "download.youtube"
        const val DEFAULT_CONCURRENT_FRAGMENTS = 1
        const val DEFAULT_RETRY_MAX_ATTEMPTS = 5
        const val DEFAULT_RETRY_RANDOMIZATION_FACTOR = 0.0

        val DEFAULT_RESOLVE_TIMEOUT = 60.seconds
        val DEFAULT_RETRY_BASE_DELAY = 250.milliseconds

        /**
         * Reads the flat `[download.youtube]` sub-block. A missing table and a missing key both answer
         * the default; a value that cannot be read raises with the name of the key and the value,
         * because a typo in a timeout is worth a loud stop rather than a silent default.
         */
        fun fromConfig(config: Config, homeDir: Path, tablePath: String = DEFAULT_TABLE): YtDlpConfig {
            val table = config.getTableOrNull(tablePath).orEmpty()
            return YtDlpConfig(
                ffmpegLocation = ffmpegDirectory(homeDir),
                binaryPath = binaryPathAt(table, tablePath),
                resolveTimeout = resolveTimeoutAt(table, tablePath),
                concurrentFragments = fragmentsAt(table, tablePath),
                retryMaxAttempts = attemptsAt(table, tablePath),
                retryBaseDelay =
                    millisecondsAt(table, tablePath, "base-delay-ms", DEFAULT_RETRY_BASE_DELAY, allowZero = true),
                retryRandomizationFactor = factorAt(table, tablePath),
            )
        }

        private fun binaryPathAt(table: Map<String, Any>, tablePath: String): Path? {
            val raw = table["binary-path"] ?: return null
            val at = name(tablePath, "binary-path")
            val text = raw.toString().trim()
            require(text.isNotEmpty()) { "$at must be a path, got an empty value" }
            return try {
                Path.of(text)
            } catch (_: InvalidPathException) {
                throw IllegalArgumentException("$at must be a path, got $raw")
            }
        }

        private fun resolveTimeoutAt(table: Map<String, Any>, tablePath: String): Duration {
            val raw = table["resolve-timeout-ms"] ?: return DEFAULT_RESOLVE_TIMEOUT
            val at = name(tablePath, "resolve-timeout-ms")
            val value = wholeNumber(raw, at)
            require(value > 0) { "$at must be positive, got $raw" }
            return value.milliseconds
        }

        private fun fragmentsAt(table: Map<String, Any>, tablePath: String): Int {
            val raw = table["concurrent-fragments"] ?: return DEFAULT_CONCURRENT_FRAGMENTS
            val at = name(tablePath, "concurrent-fragments")
            val value = wholeNumber(raw, at)
            require(value >= 1) { "$at must be a whole number of at least 1, got $raw" }
            return value
        }

        private fun attemptsAt(table: Map<String, Any>, tablePath: String): Int {
            val raw = table["max-attempts"] ?: return DEFAULT_RETRY_MAX_ATTEMPTS
            val at = name(tablePath, "max-attempts")
            val value = wholeNumber(raw, at)
            require(value >= 1) { "$at must be a whole number of at least 1, got $raw" }
            return value
        }

        private fun millisecondsAt(
            table: Map<String, Any>,
            tablePath: String,
            key: String,
            default: Duration,
            allowZero: Boolean = false,
        ): Duration {
            val raw = table[key] ?: return default
            val at = name(tablePath, key)
            val value = wholeNumber(raw, at)
            require(allowZero || value > 0) { "$at must be positive, got $raw" }
            return value.milliseconds
        }

        private fun factorAt(table: Map<String, Any>, tablePath: String): Double {
            val raw = table["randomization-factor"] ?: return DEFAULT_RETRY_RANDOMIZATION_FACTOR
            val at = name(tablePath, "randomization-factor")
            val value = raw.toString().trim().toDoubleOrNull()
            require(value != null && value in 0.0..1.0) { "$at must be within 0.0..1.0, got $raw" }
            return value
        }

        private fun wholeNumber(raw: Any, at: String): Int {
            val value =
                when (raw) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    is String -> raw.trim().toIntOrNull()
                    else -> null
                }
            require(value != null) { "$at must be a whole number, got $raw" }
            return value
        }

        private fun name(tablePath: String, key: String) = "$tablePath.$key"
    }
}
