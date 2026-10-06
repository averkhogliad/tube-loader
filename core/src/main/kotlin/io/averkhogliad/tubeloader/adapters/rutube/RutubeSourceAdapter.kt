package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.HttpTool
import io.averkhogliad.tubeloader.core.port.MediaTool
import io.averkhogliad.tubeloader.core.port.bytes
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json

private const val OPTIONS_BASE = "https://rutube.ru/api/play/options"
private const val PLAY_OPTIONS_QUERY = "no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val REFERER = "https://rutube.ru"
private val REFERER_HEADERS = mapOf("Referer" to REFERER)

/**
 * Rutube answers this status instead of 404 for a missing video when the request carries
 * `no_404=true`, naming the reason in `detail.name`.
 */
private const val NOT_FOUND_STATUS = 244
private const val NOT_FOUND_REASON = "default_does_not_exists_video"

internal const val QUALITY_1080 = "1080p"

private val HOST = Regex("""^(?:[a-z0-9-]+\.)*rutube\.ru$""", RegexOption.IGNORE_CASE)
private val MEDIA_ID = Regex("[0-9a-f]{32}")
private val VIDEO_PATH = Regex("""^/video/([^/]+)/?.*$""")
private val EMBED_PATH = Regex("""^/play/embed/([^/]+)/?$""")

private val json = Json { ignoreUnknownKeys = true }

class RutubeSourceAdapter(
    private val http: HttpTool,
    private val mediaTool: MediaTool,
) : SourceAdapter {

    override val capability: DownloadCapability = DownloadCapability.Native

    override val displayName: String = "Rutube"

    override suspend fun find(input: String): FindResult {
        val url = runCatching { URI(input) }.getOrNull() ?: return FindResult.Unsupported
        val host = url.host ?: return FindResult.Unsupported
        if (!HOST.matches(host)) return FindResult.Unsupported
        val candidate = mediaIdOf(url.path) ?: return FindResult.Unsupported
        return if (MEDIA_ID.matches(candidate)) FindResult.Found(candidate) else FindResult.Unsupported
    }

    override suspend fun loadMeta(id: String): LoadMetaResult {
        val body = http.open(optionsUrl(id), REFERER_HEADERS)
        val text = body.bytes().decodeToString()
        if (body.status == NOT_FOUND_STATUS && notFoundReason(text)) return LoadMetaResult.NotFound
        return when {
            body.status in 500..599 -> LoadMetaResult.Failed(DownloadError.NetworkTransient)
            body.status !in 200..299 -> LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            else -> parseMeta(id, text)
        }
    }

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        val playlistUrl = playlistUrlOf(mediaId) ?: return broken()
        val height = heightOf(quality.id) ?: return broken()
        val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.tmp")
        return try {
            onProgress(SourceProgress.Indeterminate)
            val variant = HlsPlaylist.selectVariant(openText(playlistUrl), height) ?: return broken()

            onProgress(SourceProgress.Indeterminate)
            val segments = HlsPlaylist.segments(openText(variant))
            if (segments.isEmpty()) return broken()

            val transferred = transfer(segments, tmpPath, onProgress)
            if (transferred !is DownloadResult.Success) return transferred

            finalize(tmpPath, targetPath, onProgress)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            DownloadResult.Failed(DownloadError.NetworkTransient)
        } finally {
            Files.deleteIfExists(tmpPath)
        }
    }

    private suspend fun finalize(
        tmpPath: Path,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        // the duration of a container rewrite is unpredictable, even for a stream copy
        onProgress(SourceProgress.Indeterminate)
        val remuxed = mediaTool.remux(tmpPath, targetPath) { progress -> onProgress(progress.toSourceProgress()) }
        if (remuxed.isFailure) return DownloadResult.Failed(DownloadError.ExtractorBroken)
        Files.deleteIfExists(tmpPath)
        val size = Files.size(targetPath)
        // the core reads the completion of a download as an absolute update, not a fraction
        onProgress(SourceProgress.Absolute(size, size))
        return DownloadResult.Success
    }

    /**
     * Copies the segments one by one into [tmpPath]. The file appears with the first segment, so a run
     * that fails while navigating the playlists leaves nothing behind.
     */
    private suspend fun transfer(
        segments: List<String>,
        tmpPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        var done = 0
        var sink: OutputStream? = null
        try {
            for (segment in segments) {
                val body = http.open(segment, REFERER_HEADERS)
                if (body.status in 500..599) return DownloadResult.Failed(DownloadError.NetworkTransient)
                // a missing segment is a broken source, not a transient one
                if (body.status !in 200..299) return broken()
                if (sink == null) {
                    sink = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                sink.write(body.bytes())
                done += 1
                onProgress(SourceProgress.Fraction(done.toDouble() / segments.size))
            }
        } finally {
            sink?.close()
        }
        return DownloadResult.Success
    }

    private fun parseMeta(id: String, text: String): LoadMetaResult {
        val options =
            try {
                json.decodeFromString<PlayOptions>(text)
            } catch (_: Exception) {
                return LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }
        // the response `id` is an internal numeric key, only `video_id` identifies the media
        if (options.videoId != id) return LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        val title =
            options.title?.takeIf { it.isNotBlank() }
                ?: return LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        val balancer = options.videoBalancer
        val playlist = balancer?.m3u8 ?: balancer?.fallback
        if (playlist.isNullOrBlank()) return LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        return LoadMetaResult.Found(
            MediaMeta(
                id = id,
                title = title,
                author = options.author?.name.orEmpty(),
                duration = options.duration?.milliseconds ?: Duration.ZERO,
                thumbnailUrl = options.thumbnailUrl?.takeIf { it.isNotBlank() },
                qualities = listOf(Quality(QUALITY_1080, TrackKind.Video, QUALITY_1080)),
            ),
        )
    }

    private fun notFoundReason(text: String): Boolean =
        runCatching { json.decodeFromString<ErrorDetail>(text).detail?.name }.getOrNull() == NOT_FOUND_REASON

    private suspend fun playlistUrlOf(id: String): String? {
        val body = http.open(optionsUrl(id), REFERER_HEADERS)
        val text = body.bytes().decodeToString()
        if (body.status !in 200..299) return null
        val options =
            try {
                json.decodeFromString<PlayOptions>(text)
            } catch (_: Exception) {
                return null
            }
        return options.videoBalancer?.let { it.m3u8 ?: it.fallback }?.takeIf { it.isNotBlank() }
    }

    private suspend fun openText(url: String): String = http.open(url, REFERER_HEADERS).bytes().decodeToString()

    private fun broken() = DownloadResult.Failed(DownloadError.ExtractorBroken)

    private fun optionsUrl(id: String) = "$OPTIONS_BASE/$id/?$PLAY_OPTIONS_QUERY"

    private fun mediaIdOf(path: String?): String? {
        val route = path ?: return null
        val match = VIDEO_PATH.matchEntire(route) ?: EMBED_PATH.matchEntire(route)
        return match?.groupValues?.get(1)
    }
}

/**
 * A container rewrite reports its own progress; the adapter speaks a different vocabulary, so the
 * update is translated at the seam.
 */
private fun Progress.toSourceProgress(): SourceProgress =
    when (this) {
        is Progress.Indeterminate -> SourceProgress.Indeterminate
        is Progress.Determinate ->
            if (total <= 0) SourceProgress.Indeterminate else SourceProgress.Fraction(current.toDouble() / total)
    }
