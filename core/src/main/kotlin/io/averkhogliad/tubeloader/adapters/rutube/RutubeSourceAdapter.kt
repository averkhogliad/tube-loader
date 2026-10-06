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
import io.averkhogliad.tubeloader.core.port.HttpBody
import io.averkhogliad.tubeloader.core.port.HttpTool
import io.averkhogliad.tubeloader.core.port.MediaTool
import io.averkhogliad.tubeloader.core.port.bytes
import io.averkhogliad.tubeloader.retry.RetryPolicy
import io.averkhogliad.tubeloader.retry.continueIf
import io.averkhogliad.tubeloader.retry.exponentialBackoff
import io.averkhogliad.tubeloader.retry.retry
import io.averkhogliad.tubeloader.retry.stopAtAttempts
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val OPTIONS_BASE = "https://rutube.ru/api/play/options"
private const val PLAY_OPTIONS_QUERY = "no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val REFERER = "https://rutube.ru"
private val REFERER_HEADERS = mapOf("Referer" to REFERER)

private const val HTTP_OK = 200
private const val HTTP_LAST_SUCCESS = 299
private const val HTTP_MISSING_VIDEO = 244
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_LAST_SERVER_ERROR = 599

/**
 * Carries the status of a response the retry loop refuses. The status has to survive into the
 * policy, which sees only a failure.
 */
private class HttpStatusException(val status: Int) : IOException("the source answered $status")

private const val MAX_ATTEMPTS = 5
private val RETRY_BASE_PAUSE = 250.milliseconds

/**
 * Repeats a hiccup of the source rather than its verdict: a server error and a rate limit both ask
 * the caller to come back, so they are retried with a growing pause until the attempts run out.
 */
private val RETRY_POLICY: RetryPolicy =
    RetryPolicy
        .stopAtAttempts(MAX_ATTEMPTS)
        .continueIf { failure -> failure is IOException }
        .exponentialBackoff(RETRY_BASE_PAUSE)

/**
 * Rutube answers 244 instead of 404 for a missing video when the request carries `no_404=true`,
 * naming the reason in `detail.name`. Both statuses arrive with the same body.
 */
private val MISSING_VIDEO_STATUSES = setOf(HTTP_MISSING_VIDEO, HTTP_NOT_FOUND)
private val SUCCESS_STATUS = HTTP_OK..HTTP_LAST_SUCCESS
private val SERVER_ERROR = HTTP_SERVER_ERROR..HTTP_LAST_SERVER_ERROR

/**
 * What the retry policy is allowed to repeat: a hiccup of the source rather than its verdict. A rate
 * limit belongs here because the source asks the caller to come back later, not to give up.
 */
private val RETRYABLE_STATUS = setOf(HTTP_TOO_MANY_REQUESTS)

private const val MISSING_VIDEO_REASON = "default_does_not_exists_video"

internal const val QUALITY_1080 = "1080p"

private val HOST = Regex("""^(?:[a-z0-9-]+\.)*rutube\.ru$""", RegexOption.IGNORE_CASE)
private val MEDIA_ID = Regex("[0-9a-f]{32}")
private val VIDEO_PATH = Regex("""^/video/([^/]+)/?.*$""")
private val EMBED_PATH = Regex("""^/play/embed/([^/]+)/?$""")

/**
 * Adapter over the Rutube web player. It streams the HLS ladder of the source and hands the joined
 * segments to [MediaTool] for a container rewrite; the staging of the target file stays with the core.
 *
 * DownloadError.UrlExpired is never answered here: the metadata request and the segment transfer
 * follow each other, so the signed balancer url cannot expire between them.
 */
class RutubeSourceAdapter(private val http: HttpTool, private val mediaTool: MediaTool) : SourceAdapter {

    override val capability: DownloadCapability = DownloadCapability.Native

    override val displayName: String = "Rutube"

    override suspend fun find(input: String): FindResult {
        val url = runCatching { URI(input) }.getOrNull()
        val id = url?.host?.takeIf { HOST.matches(it) }?.let { mediaIdOf(url.path) }
        return if (id != null && MEDIA_ID.matches(id)) FindResult.Found(id) else FindResult.Unsupported
    }

    override suspend fun loadMeta(id: String): LoadMetaResult {
        val body =
            try {
                openWithRetry(http, optionsUrl(id))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                return LoadMetaResult.Failed(DownloadError.NetworkTransient)
            }
        return classifyMeta(id, body)
    }

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.tmp")
        return try {
            runDownload(mediaId, quality, tmpPath, targetPath, onProgress)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            DownloadResult.Failed(DownloadError.NetworkTransient)
        } finally {
            Files.deleteIfExists(tmpPath)
        }
    }

    private suspend fun runDownload(
        mediaId: String,
        quality: Quality,
        tmpPath: Path,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        val segments =
            segmentsOf(mediaId, quality, onProgress)
                ?: return DownloadResult.Failed(DownloadError.ExtractorBroken)
        val transferred = transfer(segments, tmpPath, onProgress)
        return if (transferred is DownloadResult.Success) finalize(tmpPath, targetPath, onProgress) else transferred
    }

    private suspend fun segmentsOf(
        mediaId: String,
        quality: Quality,
        onProgress: (SourceProgress) -> Unit,
    ): List<String>? {
        onProgress(SourceProgress.Indeterminate)
        val variant = variantOf(mediaId, quality) ?: return null
        onProgress(SourceProgress.Indeterminate)
        // a leaf playlist names its segments relative to its own location
        return HlsPlaylist.segments(openText(variant), variant).takeIf { it.isNotEmpty() }
    }

    private suspend fun variantOf(mediaId: String, quality: Quality): String? {
        val playlist = playlistUrlOf(mediaId)
        val height = heightOf(quality.id)
        return if (playlist != null && height != null) HlsPlaylist.selectVariant(openText(playlist), height) else null
    }

    private suspend fun playlistUrlOf(mediaId: String): String? = playlistUrlIn(openText(optionsUrl(mediaId)))

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
                val body = openWithRetry(http, segment)
                // a missing segment is a broken source, not a transient one
                if (body.status !in SUCCESS_STATUS) {
                    body.body.close()
                    return DownloadResult.Failed(DownloadError.ExtractorBroken)
                }
                if (sink == null) {
                    sink =
                        Files.newOutputStream(
                            tmpPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        )
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

    private suspend fun openText(url: String): String = openWithRetry(http, url).bytes().decodeToString()
}

/**
 * Opens [url], repeating a transport failure or a server error while [RETRY_POLICY] allows it. A
 * client error is the verdict of the source, not a hiccup, so it comes back as a response for the
 * caller to classify.
 *
 * A response the loop refuses is closed before the failure leaves the block: nothing else holds it
 * once the attempt is over.
 */
private suspend fun openWithRetry(http: HttpTool, url: String): HttpBody =
    retry(RETRY_POLICY) {
        try {
            val body = http.open(url, REFERER_HEADERS)
            if (body.status in SERVER_ERROR || body.status in RETRYABLE_STATUS) {
                body.body.close()
                Result.failure(HttpStatusException(body.status))
            } else {
                Result.success(body)
            }
        } catch (failure: IOException) {
            Result.failure(failure)
        }
    }.getOrThrow()

private fun classifyMeta(id: String, body: HttpBody): LoadMetaResult {
    val text = body.bytes().decodeToString()
    return when {
        body.status in MISSING_VIDEO_STATUSES && missingVideoReason(text) -> LoadMetaResult.NotFound
        body.status !in SUCCESS_STATUS -> LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        else -> parseMeta(id, text)
    }
}

private fun parseMeta(id: String, text: String): LoadMetaResult {
    val options = rutubePayload(text)
    val title = options?.string("title")?.takeIf { it.isNotBlank() }
    // the response `id` is an internal numeric key, only `video_id` identifies the media
    return if (options?.string("video_id") == id && title != null && playlistUrlIn(options) != null) {
        LoadMetaResult.Found(
            MediaMeta(
                id = id,
                title = title,
                author = options.nested("author")?.string("name").orEmpty(),
                duration = options.number("duration")?.milliseconds ?: Duration.ZERO,
                thumbnailUrl = options.string("thumbnail_url")?.takeIf { it.isNotBlank() },
                qualities = listOf(Quality(QUALITY_1080, TrackKind.Video, QUALITY_1080)),
            ),
        )
    } else {
        LoadMetaResult.Failed(DownloadError.ExtractorBroken)
    }
}

private fun missingVideoReason(text: String): Boolean =
    rutubePayload(text)?.nested("detail")?.string("name") == MISSING_VIDEO_REASON

/**
 * The balancer block carries both a preferred stream and a fallback; either may be absent or empty.
 */
private fun playlistUrlIn(options: JsonObject?): String? {
    val balancer = options?.nested("video_balancer") ?: return null
    return (balancer.string("m3u8") ?: balancer.string("default"))?.takeIf { it.isNotBlank() }
}

private fun playlistUrlIn(text: String): String? = playlistUrlIn(rutubePayload(text))

private fun optionsUrl(id: String) = "$OPTIONS_BASE/$id/?$PLAY_OPTIONS_QUERY"

private fun mediaIdOf(path: String?): String? {
    val route = path ?: return null
    val match = VIDEO_PATH.matchEntire(route) ?: EMBED_PATH.matchEntire(route)
    return match?.groupValues?.get(1)
}

/**
 * A container rewrite reports its own progress; the adapter speaks a different vocabulary, so the
 * update is translated at the seam.
 */
private fun Progress.toSourceProgress(): SourceProgress =
    when (this) {
        is Progress.Indeterminate -> {
            SourceProgress.Indeterminate
        }

        is Progress.Determinate -> {
            if (total <= 0) SourceProgress.Indeterminate else SourceProgress.Fraction(current.toDouble() / total)
        }
    }
