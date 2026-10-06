package io.averkhogliad.tubeloader.adapters.rutube

/**
 * Master playlist of the source: every variant is a url carrying `i=<WxH>_<bitrate>` in its query.
 */
internal object HlsPlaylist {

    private val QUALITY_MARKER = Regex("""[?&]i=(\d+)x(\d+)_""")

    /**
     * Picks the variant closest to [height], preferring the higher one on a tie. Returns null when no
     * line of [master] carries a quality marker.
     */
    fun selectVariant(master: String, height: Int): String? =
        master
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { url -> resolutionOf(url)?.let { url to it } }
            .minWithOrNull(compareBy({ kotlin.math.abs(it.second - height) }, { -it.second }))
            ?.first

    /**
     * The media segments of a leaf playlist: every line that is neither a tag nor blank. The source
     * names them relative to [playlistUrl], so a relative line is made absolute against it.
     */
    fun segments(leaf: String, playlistUrl: String): List<String> {
        val base = playlistUrl.substringBeforeLast('/') + '/'
        return leaf
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else base + it }
            .toList()
    }

    private fun resolutionOf(url: String): Int? =
        QUALITY_MARKER
            .find(url)
            ?.groupValues
            ?.get(2)
            ?.toIntOrNull()
}
