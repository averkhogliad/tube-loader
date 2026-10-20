package io.averkhogliad.tubeloader.adapters.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads of the tool payload. The fields are picked by name instead of being described by a generated
 * model: the schema of `-J` is not versioned, carries dozens of keys and grows, while the adapter
 * uses five of them.
 */
internal val ytDlpJson = Json { ignoreUnknownKeys = true }

internal fun ytDlpPayload(text: String): JsonObject? =
    runCatching { ytDlpJson.parseToJsonElement(text) }.getOrNull() as? JsonObject

internal fun JsonObject.string(name: String): String? =
    // JsonNull is a JsonPrimitive too, and its content is the literal "null"
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.number(name: String): Double? = string(name)?.toDoubleOrNull()

internal fun JsonObject.wholeNumber(name: String): Long? = number(name)?.toLong()
