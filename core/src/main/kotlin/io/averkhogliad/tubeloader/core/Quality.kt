package io.averkhogliad.tubeloader.core

enum class TrackKind { Video, Audio }

data class Quality(
    val id: String,
    val kind: TrackKind,
    val label: String,
)
