package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

private const val NO_CODEC = "none"
private const val UPLOADER = "uploader"
private const val CHANNEL = "channel"
private const val FORMATS = "formats"
private const val MILLIS_PER_SECOND = 1_000

private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

/**
 * The payload names the media it resolved, so a payload of another media is a broken read rather
 * than the meta of the one asked for. A payload that names no video quality is broken as well: the
 * adapter has nothing to offer the user to download.
 */
internal fun mediaMetaOf(payload: JsonObject, requestedId: String): MediaMeta? {
    val id = payload.string("id").takeUnless { it != requestedId }
    val qualities = qualitiesOf(payload)
    return if (id == null || qualities.isEmpty()) {
        null
    } else {
        MediaMeta(
            id = id,
            title = payload.string("title").orEmpty(),
            author = payload.string(UPLOADER) ?: payload.string(CHANNEL).orEmpty(),
            duration = payload.durationOf(),
            thumbnailUrl = payload.string("thumbnail"),
            qualities = qualities,
        )
    }
}

/**
 * The height of the format is the quality: the download picks its selector by the same term. A
 * height the model cannot carry is a format the adapter cannot offer, so it is dropped rather than
 * truncated.
 */
internal fun qualityOf(height: Long): Quality? =
    height
        .takeIf { it in INT_RANGE }
        ?.toInt()
        ?.let { pixels -> Quality.ofHeight(pixels) }

private fun qualitiesOf(payload: JsonObject): List<Quality> =
    (payload[FORMATS] as? JsonArray)
        .orEmpty()
        .mapNotNull { format -> format as? JsonObject }
        .filter { format -> format.string("vcodec").let { codec -> codec != null && codec != NO_CODEC } }
        .mapNotNull { format -> format.wholeNumber("height") }
        .distinct()
        .sortedDescending()
        .mapNotNull(::qualityOf)

private fun JsonObject.durationOf() = ((number("duration") ?: 0.0) * MILLIS_PER_SECOND).roundToLong().milliseconds
