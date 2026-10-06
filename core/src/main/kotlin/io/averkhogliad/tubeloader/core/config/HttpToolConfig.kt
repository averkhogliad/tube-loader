package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the HTTP port needs beyond the protocol: the two timeouts of a single attempt, and the shape
 * of the retry an adapter wraps it in.
 *
 * The defaults live here and nowhere else, so an adapter holds no retry constant of its own.
 */
data class HttpToolConfig(
    val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    val retryMaxAttempts: Int = DEFAULT_RETRY_MAX_ATTEMPTS,
    val retryBaseDelay: Duration = DEFAULT_RETRY_BASE_DELAY,
    val retryRandomizationFactor: Double = DEFAULT_RETRY_RANDOMIZATION_FACTOR,
    val retryRetriableStatuses: Set<Int> = DEFAULT_RETRIABLE_STATUSES,
) {

    init {
        require(connectTimeout > Duration.ZERO) { "connectTimeout must be positive, got $connectTimeout" }
        require(readTimeout > Duration.ZERO) { "readTimeout must be positive, got $readTimeout" }
        require(retryMaxAttempts >= 1) { "retryMaxAttempts must be at least 1, got $retryMaxAttempts" }
        require(retryBaseDelay >= Duration.ZERO) { "retryBaseDelay must not be negative, got $retryBaseDelay" }
        require(retryRandomizationFactor in 0.0..1.0) {
            "retryRandomizationFactor must be within 0.0..1.0, got $retryRandomizationFactor"
        }
    }

    companion object {

        const val DEFAULT_TABLE = "download.http-tool"

        const val DEFAULT_RETRY_MAX_ATTEMPTS = 5
        const val DEFAULT_RETRY_RANDOMIZATION_FACTOR = 0.0

        val DEFAULT_CONNECT_TIMEOUT = 5.seconds
        val DEFAULT_READ_TIMEOUT = 30.seconds
        val DEFAULT_RETRY_BASE_DELAY = 250.milliseconds
        val DEFAULT_RETRIABLE_STATUSES = setOf(429, 500, 502, 503, 504)

        /**
         * Reads the flat `[download.http-tool]` sub-block. A missing table and a missing key both
         * answer the default; a value that cannot be read raises with the name of the key and the
         * value, because a typo in a timeout is worth a loud stop rather than a silent default.
         */
        fun fromConfig(config: Config, tablePath: String = DEFAULT_TABLE): HttpToolConfig {
            val table = config.getTableOrNull(tablePath).orEmpty()
            return HttpToolConfig(
                connectTimeout = millisecondsAt(table, tablePath, "connect-timeout-ms", DEFAULT_CONNECT_TIMEOUT),
                readTimeout = millisecondsAt(table, tablePath, "read-timeout-ms", DEFAULT_READ_TIMEOUT),
                retryMaxAttempts = attemptsAt(table, tablePath),
                retryBaseDelay = millisecondsAt(table, tablePath, "base-delay-ms", DEFAULT_RETRY_BASE_DELAY),
                retryRandomizationFactor = factorAt(table, tablePath),
                retryRetriableStatuses = statusesAt(table, tablePath) ?: DEFAULT_RETRIABLE_STATUSES,
            )
        }

        private fun millisecondsAt(
            table: Map<String, Any>,
            tablePath: String,
            key: String,
            default: Duration,
        ): Duration {
            val raw = table[key] ?: return default
            val at = name(tablePath, key)
            val value = requireNonNegative(raw, at)
            require(value > 0) { "$at must be positive, got $raw" }
            return value.milliseconds
        }

        private fun attemptsAt(table: Map<String, Any>, tablePath: String): Int =
            requirePositive(table["max-attempts"] ?: return DEFAULT_RETRY_MAX_ATTEMPTS, name(tablePath, "max-attempts"))

        private fun factorAt(table: Map<String, Any>, tablePath: String): Double {
            val raw = table["randomization-factor"] ?: return DEFAULT_RETRY_RANDOMIZATION_FACTOR
            val at = name(tablePath, "randomization-factor")
            val value = raw.toString().trim().toDoubleOrNull()
            require(value != null && value in 0.0..1.0) { "$at must be within 0.0..1.0, got $raw" }
            return value
        }

        private fun statusesAt(table: Map<String, Any>, tablePath: String): Set<Int>? {
            val raw = table["retriable-statuses"] ?: return null
            val at = name(tablePath, "retriable-statuses")
            require(raw is Collection<*>) { "$at must be a list of statuses, got $raw" }
            return raw.map { element -> requirePositive(element ?: reject(at), at) }.toSet()
        }

        private fun requirePositive(raw: Any, at: String): Int {
            val value = requireNonNegative(raw, at)
            require(value >= 1) { "$at must be a whole number of at least 1, got $raw" }
            return value
        }

        private fun requireNonNegative(raw: Any, at: String): Int {
            val value =
                when (raw) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    is String -> raw.trim().toIntOrNull()
                    else -> null
                }
            require(value != null && value >= 0) { "$at must be a whole number, got $raw" }
            return value
        }

        private fun reject(at: String): Nothing = throw IllegalArgumentException("$at must be a list of whole numbers")

        private fun name(tablePath: String, key: String) = "$tablePath.$key"
    }
}
