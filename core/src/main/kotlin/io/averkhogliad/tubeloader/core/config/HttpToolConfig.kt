package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.ConfigReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the HTTP port needs beyond the protocol: the three timeouts of a single attempt, and the shape
 * of the retry an adapter wraps it in.
 *
 * [connectTimeout] and [requestTimeout] belong to the client; [perAttemptTimeout] is the adapter's own
 * budget for one attempt, and it has no default.
 *
 * The defaults live here and nowhere else, so an adapter holds no retry constant of its own.
 */
data class HttpToolConfig(
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    val perAttemptTimeout: Duration,
    val retryMaxAttempts: Int = DEFAULT_RETRY_MAX_ATTEMPTS,
    val retryBaseDelay: Duration = DEFAULT_RETRY_BASE_DELAY,
    val retryRandomizationFactor: Double = DEFAULT_RETRY_RANDOMIZATION_FACTOR,
    val retryRetriableStatuses: Set<Int> = DEFAULT_RETRIABLE_STATUSES,
) {

    init {
        require(connectTimeout > Duration.ZERO) { "connectTimeout must be positive, got $connectTimeout" }
        require(requestTimeout > Duration.ZERO) { "requestTimeout must be positive, got $requestTimeout" }
        require(perAttemptTimeout > Duration.ZERO) {
            "perAttemptTimeout must be positive, got $perAttemptTimeout"
        }
        require(retryMaxAttempts >= 1) { "retryMaxAttempts must be at least 1, got $retryMaxAttempts" }
        require(retryBaseDelay >= Duration.ZERO) { "retryBaseDelay must not be negative, got $retryBaseDelay" }
        require(retryRandomizationFactor in 0.0..1.0) {
            "retryRandomizationFactor must be within 0.0..1.0, got $retryRandomizationFactor"
        }
        require(retryRetriableStatuses.all { it > 0 }) {
            "retryRetriableStatuses must be positive, got $retryRetriableStatuses"
        }
    }

    companion object {

        const val DEFAULT_TABLE = "download.http-tool"

        const val DEFAULT_RETRY_MAX_ATTEMPTS = 5
        const val DEFAULT_RETRY_RANDOMIZATION_FACTOR = 0.0

        val DEFAULT_CONNECT_TIMEOUT = 5.seconds
        val DEFAULT_REQUEST_TIMEOUT = 30.seconds
        val DEFAULT_RETRY_BASE_DELAY = 250.milliseconds
        val DEFAULT_RETRIABLE_STATUSES = setOf(408, 429, 502, 503, 504)

        /**
         * Reads the flat `[download.http-tool]` sub-block through the source-agnostic reader: a
         * missing key answers the default, except `per-attempt-timeout-ms`, which is required. The
         * syntax of a value belongs to the reader; the semantics — positivity, bounds — are the
         * checks of `init`, so a bad value is stopped the same way however the config was built.
         */
        fun fromReader(reader: ConfigReader, tablePath: String = DEFAULT_TABLE): HttpToolConfig =
            HttpToolConfig(
                connectTimeout =
                    reader.durationMillis(key(tablePath, "connect-timeout-ms")) ?: DEFAULT_CONNECT_TIMEOUT,
                requestTimeout =
                    reader.durationMillis(key(tablePath, "request-timeout-ms")) ?: DEFAULT_REQUEST_TIMEOUT,
                perAttemptTimeout =
                    requireNotNull(reader.durationMillis(key(tablePath, "per-attempt-timeout-ms"))) {
                        "${key(tablePath, "per-attempt-timeout-ms")} is required"
                    },
                retryMaxAttempts =
                    reader.wholeNumber(key(tablePath, "max-attempts")) ?: DEFAULT_RETRY_MAX_ATTEMPTS,
                retryBaseDelay =
                    reader.durationMillis(key(tablePath, "base-delay-ms")) ?: DEFAULT_RETRY_BASE_DELAY,
                retryRandomizationFactor =
                    reader.factor(key(tablePath, "randomization-factor")) ?: DEFAULT_RETRY_RANDOMIZATION_FACTOR,
                retryRetriableStatuses =
                    reader.numberList(key(tablePath, "retriable-statuses"))?.toSet()
                        ?: DEFAULT_RETRIABLE_STATUSES,
            )

        private fun key(tablePath: String, name: String) = "$tablePath.$name"
    }
}
