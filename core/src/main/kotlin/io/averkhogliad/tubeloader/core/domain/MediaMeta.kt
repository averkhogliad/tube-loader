package io.averkhogliad.tubeloader.core.domain

import kotlin.time.Duration

data class MediaMeta(
    val id: String,
    val title: String,
    val author: String,
    val duration: Duration,
    val thumbnailUrl: String?,
    val qualities: List<Quality>,
)
