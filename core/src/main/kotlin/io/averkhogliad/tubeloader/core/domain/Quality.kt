package io.averkhogliad.tubeloader.core.domain

enum class TrackKind {
    Video,
    Audio,
}

data class Quality(
    val id: String,
    val kind: TrackKind,
    val label: String,
    val height: Int? = null,
) {
    companion object {
        /**
         * A video quality of [height] pixels: the id and the label name the height, and the height
         * itself is the universal property of the variant. A source-specific id is built by the
         * source, not read by the core.
         */
        fun ofHeight(height: Int): Quality {
            val name = "${height}p"
            return Quality(id = name, kind = TrackKind.Video, label = name, height = height)
        }
    }
}
