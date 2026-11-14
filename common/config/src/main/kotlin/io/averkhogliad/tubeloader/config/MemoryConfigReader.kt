package io.averkhogliad.tubeloader.config

import java.nio.file.Path
import kotlin.time.Duration

/**
 * The reader of a source kept in code: the values are given as the source would spell them — whole
 * numbers, text, lists — and go through the same parsing and the same messages as [TomlConfigReader].
 *
 * Tests and the composition use it where a TOML file would be noise: the typed settings do not know
 * the difference.
 */
class MemoryConfigReader(private val values: Map<String, Any>) : ConfigReader {

    override fun wholeNumber(key: String): Int? = wholeNumberValue(key, values[key])

    override fun durationMillis(key: String): Duration? = durationValue(key, values[key])

    override fun factor(key: String): Double? = factorValue(key, values[key])

    override fun path(key: String): Path? = pathValue(key, values[key])

    override fun numberList(key: String): List<Int>? = numberListValue(key, values[key])

    override fun text(key: String): String? = values[key]?.toString()
}
