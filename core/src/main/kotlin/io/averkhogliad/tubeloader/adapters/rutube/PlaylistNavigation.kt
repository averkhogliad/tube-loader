package io.averkhogliad.tubeloader.adapters.rutube

/**
 * The resolution a quality stands for. M1 ships a single video quality, so the height is read off the
 * quality id; a source with a real ladder will carry the height in the quality itself.
 */
internal fun heightOf(qualityId: String): Int? =
    Regex("""^(\d+)p$""").find(qualityId)?.groupValues?.get(1)?.toIntOrNull()
