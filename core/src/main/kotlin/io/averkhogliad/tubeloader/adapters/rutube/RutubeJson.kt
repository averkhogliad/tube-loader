package io.averkhogliad.tubeloader.adapters.rutube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads of the source payload. The fields are picked by name instead of being described by a
 * generated model: the payload carries two dozen keys and grows, while the adapter uses six of them.
 */
internal val rutubeJson = Json { ignoreUnknownKeys = true }

internal fun rutubePayload(text: String): JsonObject? =
    runCatching { rutubeJson.parseToJsonElement(text) }.getOrNull() as? JsonObject

internal fun JsonObject.string(name: String): String? =
    // JsonNull is a JsonPrimitive too, and its content is the literal "null"
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.nested(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.number(name: String): Long? = string(name)?.toLongOrNull()
