package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.Source
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import io.averkhogliad.tubeloader.core.download.TaskRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

class CoreFacade(
    adapters: Map<String, SourceAdapter>,
    private val queue: DownloadQueue,
    taskIdGenerator: TaskIdGenerator,
    private val clock: Clock = Clock.System,
) {
    private val registrations: Map<String, Registration> =
        adapters.mapValues { (code, adapter) -> Registration(Source(code, adapter.displayName), adapter) }

    private val sources: List<Source> = registrations.values.map { it.source }

    val availableSources: List<Source> get() = sources

    private val registry = TaskRegistry(taskIdGenerator, clock)

    /**
     * The media item of the url: the source is the one that named a media id in it, and the metadata
     * come from that source.
     */
    suspend fun findByUrl(input: String): MediaResult {
        val claims = mutableListOf<Claim>()
        var notFound = false
        for ((code, registration) in registrations) {
            when (val result = registration.adapter.resolveId(input)) {
                is ResolveIdResult.Found -> claims += Claim(code, result.mediaId)
                ResolveIdResult.NotFound -> notFound = true
                ResolveIdResult.Unsupported -> Unit
            }
        }
        val claim = claims.singleOrNull()
        return when {
            claims.size > 1 -> error("Ambiguous match: ${claims.size} adapters claim input '$input'")
            claim != null -> mediaResultOf(claim.code, claim.mediaId)
            notFound -> MediaResult.NotFound
            else -> MediaResult.Unsupported
        }
    }

    /**
     * The media item of the code of a source and a media id: the source is named, so it is not
     * searched for, and the id is not read as a url.
     */
    suspend fun findById(code: String, mediaId: String): MediaResult {
        if (code !in registrations) error("Unknown source code: $code")
        return mediaResultOf(code, mediaId)
    }

    fun enqueue(ref: MediaRef, quality: Quality, targetPath: Path): DownloadHandle {
        val adapter = adapterFor(ref.source.code)
        if (targetPath.parent == null) {
            error("targetPath must include a parent directory: $targetPath")
        }
        val startedAt = clock.now()
        val taskId = registry.allocate(startedAt)
        val job =
            try {
                queue.submit { runDownload(taskId, adapter, ref.mediaId, quality, targetPath) }
            } catch (refused: IllegalStateException) {
                registry.forget(taskId)
                throw refused
            }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                registry.transition(taskId, DownloadStatus.Cancelled)
            }
        }
        return DownloadHandle(taskId, registry.statesFor(taskId)) {
            registry.transition(taskId, DownloadStatus.Cancelling)
            job.cancel()
        }
    }

    private fun adapterFor(code: String): SourceAdapter = registrations[code]?.adapter ?: error("Unknown source: $code")

    private suspend fun mediaResultOf(code: String, mediaId: String): MediaResult {
        val registration = registrations.getValue(code)
        val meta = registration.adapter.findById(mediaId)
        return when (meta) {
            is LoadMetaResult.Found -> MediaResult.Found(MediaRef(registration.source, mediaId), meta.meta)
            LoadMetaResult.NotFound -> MediaResult.NotFound
            is LoadMetaResult.Failed -> MediaResult.Failed(meta.error, meta.cause)
        }
    }

    private suspend fun runDownload(
        taskId: TaskId,
        adapter: SourceAdapter,
        mediaId: String,
        quality: Quality,
        targetPath: Path,
    ) {
        val part = partialFilePath(targetPath, taskId)
        runCatching {
            registry.transition(taskId, DownloadStatus.LoadingMeta)
            // without a suspension point the phase is conflated away for a collector on another thread
            yield()
            Files.createFile(part)
            registry.transition(taskId, DownloadStatus.Downloading)
            val outcome =
                adapter.download(mediaId, quality, part) { source ->
                    registry.updateProgress(taskId, source)
                }
            if (outcome is DownloadResult.Failed) {
                deleteQuietly(part)
                registry.transition(taskId, DownloadStatus.Failed(outcome.error, outcome.cause))
                return@runCatching
            }
            registry.transition(taskId, DownloadStatus.Finalizing)
            // a cancel landing in this window would otherwise go unnoticed and the file would be moved anyway
            currentCoroutineContext().ensureActive()
            Files.move(
                part,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            registry.transition(taskId, DownloadStatus.Completed)
        }.onFailure { failure ->
            when (failure) {
                is CancellationException -> {
                    deleteQuietly(part)
                    throw failure
                }

                is Exception -> {
                    deleteQuietly(part)
                    registry.transition(taskId, DownloadStatus.Failed(DownloadError.ExtractorBroken, failure))
                }

                else -> {
                    throw failure
                }
            }
        }
    }

    private fun partialFilePath(target: Path, taskId: TaskId): Path =
        target.resolveSibling("${target.fileName}.part-$taskId")

    private fun deleteQuietly(part: Path) {
        try {
            Files.deleteIfExists(part)
        } catch (_: Exception) {
        }
    }

    /** A registered source: the code names it, the adapter has its data. */
    private class Registration(val source: Source, val adapter: SourceAdapter)

    /** A source that claimed a media id: the code of the source and the id it named. */
    private data class Claim(val code: String, val mediaId: String)
}
