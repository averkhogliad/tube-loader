package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.*
import io.averkhogliad.tubeloader.core.download.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

class CoreFacade(
    private val adapters: List<SourceAdapter>,
    private val queue: DownloadQueue,
    taskIdGenerator: TaskIdGenerator,
    private val clock: Clock = Clock.System,
) {
    private val sources: List<Source> = adapters.mapIndexed { index, adapter ->
        Source(SourceId(index), adapter.displayName)
    }

    val availableSources: List<Source> get() = sources

    private val adaptersBySourceId: Map<SourceId, SourceAdapter> =
        sources.zip(adapters).associate { (source, adapter) -> source.id to adapter }

    private val registry = TaskRegistry(taskIdGenerator, clock)

    suspend fun findByUrl(input: String): ResolveResult {
        val matches = mutableListOf<MediaRef>()
        var notFound = false
        for ((source, adapter) in sources.zip(adapters)) {
            when (val result = adapter.find(input)) {
                is FindResult.Found -> matches += MediaRef(source, result.mediaId)
                FindResult.NotFound -> notFound = true
                FindResult.Unsupported -> Unit
            }
        }
        return when {
            matches.size > 1 -> error("Ambiguous match: ${matches.size} adapters claim input '$input'")
            matches.size == 1 -> ResolveResult.Resolved(matches.single())
            notFound -> ResolveResult.NotFound
            else -> ResolveResult.Unsupported
        }
    }

    suspend fun findById(sourceId: SourceId, id: String): ResolveResult {
        val adapter =
            adaptersBySourceId[sourceId] ?: error("Unknown source id: $sourceId")
        val source = sources[sourceId.index]
        return when (val result = adapter.find(id)) {
            is FindResult.Found -> ResolveResult.Resolved(MediaRef(source, result.mediaId))
            FindResult.NotFound -> ResolveResult.NotFound
            FindResult.Unsupported -> ResolveResult.Unsupported
        }
    }

    suspend fun loadMeta(ref: MediaRef): LoadMetaResult = adapterFor(ref.source.id).loadMeta(ref.mediaId)

    fun enqueue(ref: MediaRef, quality: Quality, targetPath: Path): DownloadHandle {
        val adapter = adapterFor(ref.source.id)
        if (targetPath.parent == null) {
            error("targetPath must include a parent directory: $targetPath")
        }
        val startedAt = clock.now()
        val taskId = registry.allocate(startedAt)
        val job = try {
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

    private fun adapterFor(sourceId: SourceId): SourceAdapter =
        adaptersBySourceId[sourceId] ?: error("Unknown source: $sourceId")

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
            val outcome = adapter.download(mediaId, quality, part) { source ->
                registry.updateProgress(taskId, source.toProgress())
            }
            if (outcome is DownloadResult.Failed) {
                deleteQuietly(part)
                registry.transition(taskId, DownloadStatus.Failed(outcome.error))
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

                else -> throw failure
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
}

private const val FRACTION_SCALE = 1000L

private fun SourceProgress.toProgress(): Progress = when (this) {
    SourceProgress.Indeterminate -> Progress.Indeterminate

    is SourceProgress.Absolute ->
        if (total > 0 && processed in 0..total) Progress.Determinate(processed, total) else Progress.Indeterminate

    is SourceProgress.Fraction ->
        if (ratio.isFinite() && ratio in 0.0..1.0) {
            Progress.Determinate((ratio * FRACTION_SCALE).toLong(), FRACTION_SCALE)
        } else {
            Progress.Indeterminate
        }
}
