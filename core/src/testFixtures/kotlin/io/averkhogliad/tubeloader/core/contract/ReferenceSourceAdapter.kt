package io.averkhogliad.tubeloader.core.contract

import io.averkhogliad.tubeloader.core.DownloadCapability
import io.averkhogliad.tubeloader.core.DownloadError
import io.averkhogliad.tubeloader.core.DownloadResult
import io.averkhogliad.tubeloader.core.FindResult
import io.averkhogliad.tubeloader.core.LoadMetaResult
import io.averkhogliad.tubeloader.core.MediaMeta
import io.averkhogliad.tubeloader.core.Quality
import io.averkhogliad.tubeloader.core.SourceAdapter
import io.averkhogliad.tubeloader.core.SourceProgress
import io.averkhogliad.tubeloader.core.TrackKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val HOST = "reference.example"

private const val QUALITY_PREFIX = "quality="

/**
 * Fixture-driven [SourceAdapter] that answers only from a recording. It is deliberately strict: an
 * unrecorded URL is not a business outcome but a broken fixture, so the suite cannot pass on a gap
 * in the recording.
 *
 * It is never used by production code — it exists to prove the contract suite is runnable.
 */
class ReferenceSourceAdapter(
    private val fixtures: SourceAdapterFixtures,
) : SourceAdapter {

    override val capability: DownloadCapability = DownloadCapability.Native

    override val displayName: String = "reference"

    val progress: MutableList<SourceProgress> = mutableListOf()

    override suspend fun find(input: String): FindResult {
        val url = runCatching { java.net.URI(input) }.getOrNull() ?: return FindResult.Unsupported
        if (url.host != HOST) return FindResult.Unsupported
        val id = url.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")
            ?: return FindResult.NotFound
        return if (id.isEmpty()) FindResult.NotFound else FindResult.Found(id)
    }

    override suspend fun loadMeta(id: String): LoadMetaResult {
        val text = try {
            fixtures.responses.bodyFor(metaUrl(id)).decodeToString()
        } catch (_: IOException) {
            return LoadMetaResult.NotFound
        } catch (_: IllegalArgumentException) {
            return LoadMetaResult.NotFound
        }
        return LoadMetaResult.Found(parseMeta(id, text))
    }

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        val bytes = try {
            fixtures.responses.bodyFor(streamUrl(mediaId, quality.id))
        } catch (_: IOException) {
            return DownloadResult.Failed(DownloadError.NetworkTransient)
        } catch (_: IllegalArgumentException) {
            return DownloadResult.Failed(DownloadError.NotFound)
        }
        val total = bytes.size.toLong()
        emit(SourceProgress.Absolute(0, total), onProgress)
        Files.newOutputStream(targetPath).use { it.write(bytes) }
        emit(SourceProgress.Absolute(total, total), onProgress)
        return DownloadResult.Success
    }

    private fun emit(update: SourceProgress, onProgress: (SourceProgress) -> Unit) {
        progress += update
        onProgress(update)
    }

    private fun parseMeta(id: String, text: String): MediaMeta {
        val fields = text.lineSequence()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val qualities = text.lineSequence()
            .filter { it.startsWith(QUALITY_PREFIX) }
            .map { line ->
                val (qualityId, kind, label) = line.removePrefix(QUALITY_PREFIX).split('|')
                Quality(qualityId, TrackKind.valueOf(kind), label)
            }
            .toList()
        return MediaMeta(
            id = id,
            title = fields.getValue("title"),
            author = fields.getValue("author"),
            duration = fields.getValue("duration").toLong().seconds,
            thumbnailUrl = fields["thumbnail"],
            qualities = qualities,
        )
    }

    private fun metaUrl(id: String) = "https://api.reference.example/video/$id"

    private fun streamUrl(id: String, qualityId: String) =
        "https://cdn.reference.example/stream/$id/$qualityId"
}
