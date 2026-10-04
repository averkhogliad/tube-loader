package io.averkhogliad.tubeloader.config

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.string

fun Arb.Companion.configKeys(): Arb<String> = Arb.string(1..12, Codepoint.alphanumeric())

fun Arb.Companion.configValues(): Arb<String> = Arb.string(1..16, Codepoint.alphanumeric())

fun Arb.Companion.flatTables(
    keys: Arb<String> = Arb.configKeys(),
    values: Arb<String> = Arb.configValues(),
): Arb<Map<String, Any>> = Arb.bind(keys, values) { key, value -> mapOf(key to value) }

fun Arb.Companion.nestedTables(
    values: Arb<String> = Arb.configValues(),
): Arb<Map<String, Any>> = Arb.bind(values, values) { outer, inner ->
    mapOf("nested" to mapOf("a" to outer, "b" to inner))
}
