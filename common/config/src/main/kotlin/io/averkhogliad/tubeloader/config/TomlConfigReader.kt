package io.averkhogliad.tubeloader.config

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads typed values out of a TOML-shaped [Config]: the only adapter that knows how a leaf of a TOML
 * document is spelled.
 *
 * The parsing and the messages of this file are shared with [MemoryConfigReader], so a setting
 * behaves the same whether it came from a file or from code.
 */
class TomlConfigReader(private val config: Config) : ConfigReader {

    override fun wholeNumber(key: String): Int? = wholeNumberValue(key, config.valueOrNull(key))

    override fun durationMillis(key: String): Duration? = durationValue(key, config.valueOrNull(key))

    override fun factor(key: String): Double? = factorValue(key, config.valueOrNull(key))

    override fun path(key: String): Path? = pathValue(key, config.valueOrNull(key))

    override fun numberList(key: String): List<Int>? = numberListValue(key, config.valueOrNull(key))

    override fun text(key: String): String? = config.valueOrNull(key)?.toString()
}

internal fun wholeNumberValue(key: String, raw: Any?): Int? {
    if (raw == null) return null
    require(raw !is Long || raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "$key must be a whole number in the Int range, got $raw"
    }
    val value =
        when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    require(value != null) { "$key must be a whole number, got $raw" }
    return value
}

internal fun durationValue(key: String, raw: Any?): Duration? = wholeNumberValue(key, raw)?.milliseconds

internal fun factorValue(key: String, raw: Any?): Double? {
    if (raw == null) return null
    val value =
        when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
    require(value != null) { "$key must be a number, got $raw" }
    return value
}

internal fun pathValue(key: String, raw: Any?): Path? {
    if (raw == null) return null
    require(raw is String) { "$key must be a path, got $raw" }
    val text = raw.trim()
    require(text.isNotEmpty()) { "$key must be a path, got an empty value" }
    return try {
        Path.of(text)
    } catch (_: InvalidPathException) {
        throw IllegalArgumentException("$key must be a path, got $raw")
    }
}

internal fun numberListValue(key: String, raw: Any?): List<Int>? {
    if (raw == null) return null
    require(raw is Collection<*>) { "$key must be a list of whole numbers, got $raw" }
    return raw.map { element ->
        element?.let { wholeNumberValue(key, it) }
            ?: throw IllegalArgumentException("$key must be a list of whole numbers, got $raw")
    }
}
