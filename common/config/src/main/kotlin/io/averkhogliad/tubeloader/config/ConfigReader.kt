package io.averkhogliad.tubeloader.config

import java.nio.file.Path
import kotlin.time.Duration

/**
 * The seam the typed settings read their values through: a source-agnostic lookup by dotted key, so
 * neither the format of the source — a TOML file, code, memory — nor the file itself leaks into the
 * settings.
 *
 * A missing key answers `null`. A value that cannot be read as the requested type raises
 * [IllegalArgumentException] carrying the key and the value that failed, because a typo in a setting
 * is worth a loud stop rather than a silent default. Whole numbers are strict about the `Int` range:
 * a value that does not fit is an error, never a truncation.
 */
interface ConfigReader {

    /** A whole number of the source. */
    fun wholeNumber(key: String): Int?

    /** A duration written in milliseconds. */
    fun durationMillis(key: String): Duration?

    /** A floating point factor; a domain range of a factor belongs to the settings, not to the reader. */
    fun factor(key: String): Double?

    /** A path on the local file system. */
    fun path(key: String): Path?

    /** A list of whole numbers. */
    fun numberList(key: String): List<Int>?

    /** The text of the value, as the source spells it. */
    fun text(key: String): String?
}
