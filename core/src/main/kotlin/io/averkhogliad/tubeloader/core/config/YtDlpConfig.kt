package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.ConfigReader
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the yt-dlp adapter needs beyond the protocol: which binary to run, where ffmpeg sits, where a
 * transfer may leave its parts, how long a resolve may take, and how many fragments a download pulls
 * at once.
 *
 * [binaryPath] overrides the binary the distribution policy would place on the machine; set it where
 * the tool is already installed (CI, tests). A null path leaves the choice to the implementation.
 *
 * [paths] carries the binary of ffmpeg, which the download needs to merge video with audio, and the
 * path of the managed build of the tool itself.
 * [workDirectory] is where the tool may write; it defaults under the tool directory of [paths], next
 * to the binary the distribution keeps there.
 */
data class YtDlpConfig(
    val paths: ToolPaths,
    val workDirectory: Path = paths.homeDir.resolve(DEFAULT_WORK_DIR),
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

        /**
         * The directory a transfer of the tool keeps its parts in, under the tool directory of the
         * home directory: the root is a scratch area of its own and not the directory of the binary.
         */
        val DEFAULT_WORK_DIR: Path = Path.of(ToolPaths.YT_DLP_DIR, "work")

        val DEFAULT_RESOLVE_TIMEOUT = 60.seconds
        val DEFAULT_RETRY_BASE_DELAY = 250.milliseconds

        /**
         * Reads the flat `[download.youtube]` sub-block through the source-agnostic reader: a
         * missing key answers the default. The syntax of a value belongs to the reader; the
         * semantics — positivity, bounds — are the checks of `init`, so a bad value is stopped the
         * same way however the config was built.
         */
        fun fromReader(reader: ConfigReader, paths: ToolPaths, tablePath: String = DEFAULT_TABLE): YtDlpConfig =
            YtDlpConfig(
                paths = paths,
                workDirectory =
                    reader.path(key(tablePath, "work-directory")) ?: paths.homeDir.resolve(DEFAULT_WORK_DIR),
                binaryPath = reader.path(key(tablePath, "binary-path")),
                resolveTimeout =
                    reader.durationMillis(key(tablePath, "resolve-timeout-ms")) ?: DEFAULT_RESOLVE_TIMEOUT,
                concurrentFragments =
                    reader.wholeNumber(key(tablePath, "concurrent-fragments")) ?: DEFAULT_CONCURRENT_FRAGMENTS,
                retryMaxAttempts =
                    reader.wholeNumber(key(tablePath, "max-attempts")) ?: DEFAULT_RETRY_MAX_ATTEMPTS,
                retryBaseDelay =
                    reader.durationMillis(key(tablePath, "base-delay-ms")) ?: DEFAULT_RETRY_BASE_DELAY,
                retryRandomizationFactor =
                    reader.factor(key(tablePath, "randomization-factor")) ?: DEFAULT_RETRY_RANDOMIZATION_FACTOR,
            )

        private fun key(tablePath: String, name: String) = "$tablePath.$name"
    }
}
