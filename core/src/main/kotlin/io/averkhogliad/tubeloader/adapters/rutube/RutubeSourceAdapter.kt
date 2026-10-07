package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
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
import io.averkhogliad.tubeloader.retry.RetryContext
import io.averkhogliad.tubeloader.retry.RetryExhausted
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

/**
 * Rutube answers 244 instead of 404 for a missing video when the request carries `no_404=true`,
 * naming the reason in `detail.name`. Both statuses arrive with the same body.
 */
private val MISSING_VIDEO_STATUSES = setOf(HTTP_MISSING_VIDEO, HTTP_NOT_FOUND)
private val SUCCESS_STATUS = HTTP_OK..HTTP_LAST_SUCCESS

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
 *
 * The retry an attempt is wrapped in comes from [config], read on every call rather than captured,
 * so a setting changed while the application runs reaches the next attempt.
 */
class RutubeSourceAdapter(
    private val http: HttpTool,
    private val mediaTool: MediaTool,
    private val config: () -> HttpToolConfig,
) : SourceAdapter {

    override val capability: DownloadCapability = DownloadCapability.Native

    override val displayName: String = "Rutube"

    override suspend fun find(input: String): FindResult {
        val url = runCatching { URI(input) }.getOrNull()
        val id = url?.host?.takeIf { HOST.matches(it) }?.let { mediaIdOf(url.path) }
        return if (id != null && MEDIA_ID.matches(id)) FindResult.Found(id) else FindResult.Unsupported
    }

    override suspend fun loadMeta(id: String): LoadMetaResult {
        val body = openWithRetry(http, optionsUrl(id), config()).getOrNull()
        return if (body == null) LoadMetaResult.Failed(DownloadError.NetworkTransient) else classifyMeta(id, body)
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

    /**
     * Navigates the playlists of [mediaId], copies the segments of the chosen variant and hands the
     * result over to [finalize] once every one of them is in. A source that names no segment at all
     * is broken rather than busy; an unreachable one is transient.
     */
    private suspend fun runDownload(
        mediaId: String,
        quality: Quality,
        tmpPath: Path,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        val segments = segmentsOf(mediaId, quality, onProgress)
        val found = segments.getOrNull()
        return when {
            found == null -> DownloadResult.Failed(DownloadError.NetworkTransient)
            found.isEmpty() -> DownloadResult.Failed(DownloadError.ExtractorBroken)
            else -> finalize(transfer(found, tmpPath, onProgress), tmpPath, targetPath, onProgress)
        }
    }

    /**
     * The media segments of the variant closest to [quality]: an empty list when the source names
     * none, and a failure when the source could not be read at all.
     */
    private suspend fun segmentsOf(
        mediaId: String,
        quality: Quality,
        onProgress: (SourceProgress) -> Unit,
    ): Result<List<String>> {
        onProgress(SourceProgress.Indeterminate)
        val variant = variantOf(mediaId, quality)
        val playlist = variant.getOrNull()
        return if (playlist.isNullOrEmpty()) {
            variant.map { emptyList() }
        } else {
            onProgress(SourceProgress.Indeterminate)
            // a leaf playlist names its segments relative to its own location
            openText(playlist).map { leaf -> HlsPlaylist.segments(leaf, playlist) }
        }
    }

    /**
     * The leaf playlist of the variant closest to [quality], or an empty answer when the source names
     * no usable one.
     */
    private suspend fun variantOf(mediaId: String, quality: Quality): Result<String> {
        val height = heightOf(quality.id)
        val playlist = playlistUrlOf(mediaId)
        val url = playlist.getOrNull()
        return if (height == null || url.isNullOrEmpty()) {
            playlist.map { "" }
        } else {
            openText(url).map { master -> HlsPlaylist.selectVariant(master, height).orEmpty() }
        }
    }

    private suspend fun playlistUrlOf(mediaId: String): Result<String> =
        openText(optionsUrl(mediaId)).map { text ->
            playlistUrlIn(rutubePayload(text)).orEmpty()
        }

    private suspend fun openText(url: String): Result<String> =
        openWithRetry(http, url, config()).map { body -> body.bytes().decodeToString() }

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
        var failure: DownloadResult? = null
        try {
            for (segment in segments) {
                val body = openWithRetry(http, segment, config()).getOrNull()
                failure = refusalOf(body)
                val accepted = body?.takeIf { failure == null } ?: break
                if (sink == null) {
                    sink =
                        Files.newOutputStream(
                            tmpPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        )
                }
                sink.write(accepted.bytes())
                done += 1
                onProgress(SourceProgress.Fraction(done.toDouble() / segments.size))
            }
        } finally {
            sink?.close()
        }
        return failure ?: DownloadResult.Success
    }

    private suspend fun finalize(
        transferred: DownloadResult,
        tmpPath: Path,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        // the duration of a container rewrite is unpredictable, even for a stream copy
        if (transferred !is DownloadResult.Success) return transferred
        onProgress(SourceProgress.Indeterminate)
        val remuxed = mediaTool.remux(tmpPath, targetPath) { progress -> onProgress(progress.toSourceProgress()) }
        return if (remuxed.isFailure) {
            DownloadResult.Failed(DownloadError.ExtractorBroken)
        } else {
            Files.deleteIfExists(tmpPath)
            val size = Files.size(targetPath)
            // the core reads the completion of a download as an absolute update, not a fraction
            onProgress(SourceProgress.Absolute(size, size))
            DownloadResult.Success
        }
    }
}

/**
 * Opens [url], repeating a hiccup of the source while the policy built from [settings] allows it. Only
 * a transient status is a hiccup, so it comes back as a response for the caller to classify: the
 * context judges the status of the attempt and closes a body it turns down, so no bad status is
 * thrown as an exception and no body outlives its attempt.
 */
private suspend fun openWithRetry(http: HttpTool, url: String, settings: HttpToolConfig): Result<HttpBody> =
    retry(
        policyOf(settings),
        RetryContext(
            judging = { body ->
                // a status turned down here is closed here: the loop discards the body, so nothing
                // else would release it
                if (body.status in settings.retryRetriableStatuses) {
                    body.body.close()
                    false
                } else {
                    true
                }
            },
        ),
    ) {
        try {
            Result.success(http.open(url, REFERER_HEADERS))
        } catch (failure: IOException) {
            Result.failure(failure)
        }
    }

/**
 * Why a transfer turns an opened response down, or null when it accepts it. A response the retry loop
 * stopped on and a status the source does not serve are both verdicts, not hiccups, so each of them
 * ends the transfer. A body turned down here is closed here: the loop is already over, so nothing
 * else would release it.
 */
private fun refusalOf(body: HttpBody?): DownloadResult? =
    when {
        body == null -> {
            DownloadResult.Failed(DownloadError.NetworkTransient)
        }

        body.status !in SUCCESS_STATUS -> {
            body.body.close()
            DownloadResult.Failed(DownloadError.ExtractorBroken)
        }

        else -> {
            null
        }
    }

/**
 * Repeats a hiccup of the source rather than its verdict: a status the caller is asked to come back
 * on is retried with a growing pause until the attempts run out. A response the loop turns down
 * reaches the policy as the reason of the attempt — a transport failure, or [RetryExhausted] once the
 * attempts ran out on it — so both are accepted here. The values come from the configuration, so an
 * adapter holds no retry constant of its own.
 */
private fun policyOf(settings: HttpToolConfig): RetryPolicy =
    RetryPolicy
        .stopAtAttempts(settings.retryMaxAttempts)
        .continueIf { failure is IOException || failure is RetryExhausted }
        .exponentialBackoff(settings.retryBaseDelay, randomizationFactor = settings.retryRandomizationFactor)

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
