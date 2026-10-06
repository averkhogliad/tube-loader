package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import java.net.URI
import java.nio.file.Path

private val HOST = Regex("""^(?:[a-z0-9-]+\.)*rutube\.ru$""", RegexOption.IGNORE_CASE)
private val MEDIA_ID = Regex("[0-9a-f]{32}")
private val VIDEO_PATH = Regex("""^/video/([^/]+)/?.*$""")
private val EMBED_PATH = Regex("""^/play/embed/([^/]+)/?$""")

class RutubeSourceAdapter : SourceAdapter {

    override val capability: DownloadCapability = DownloadCapability.Native

    override val displayName: String = "Rutube"

    override suspend fun find(input: String): FindResult {
        val url = runCatching { URI(input) }.getOrNull() ?: return FindResult.Unsupported
        val host = url.host ?: return FindResult.Unsupported
        if (!HOST.matches(host)) return FindResult.Unsupported
        val candidate = mediaIdOf(url.path) ?: return FindResult.Unsupported
        return if (MEDIA_ID.matches(candidate)) FindResult.Found(candidate) else FindResult.Unsupported
    }

    override suspend fun loadMeta(id: String): LoadMetaResult = pending("loadMeta")

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult = pending("download")

    private fun mediaIdOf(path: String?): String? {
        val route = path ?: return null
        val match = VIDEO_PATH.matchEntire(route) ?: EMBED_PATH.matchEntire(route)
        return match?.groupValues?.get(1)
    }

    private fun pending(operation: String): Nothing =
        throw UnsupportedOperationException("RutubeSourceAdapter.$operation is not implemented yet")
}
