package io.averkhogliad.tubeloader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

class CoreFacade(
    private val adapters: List<SourceAdapter>,
    private val queue: DownloadQueue,
    private val taskIdGenerator: TaskIdGenerator,
    private val clock: Clock = Clock.System,
) {
    private val sources: List<Source> = adapters.mapIndexed { index, adapter ->
        Source(SourceId(index), adapter.displayName)
    }

    val availableSources: List<Source> get() = sources

    private val adaptersBySourceId: Map<SourceId, SourceAdapter> =
        sources.zip(adapters).associate { (source, adapter) -> source.id to adapter }

    private val states = MutableStateFlow<Map<TaskId, DownloadState>>(emptyMap())

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
        val adapter = adaptersBySourceId[sourceId]
            ?: error("Unknown source id: $sourceId")
        val source = sources[sourceId.index]
        return when (val result = adapter.find(id)) {
            is FindResult.Found -> ResolveResult.Resolved(MediaRef(source, result.mediaId))
            FindResult.NotFound -> ResolveResult.NotFound
            FindResult.Unsupported -> ResolveResult.Unsupported
        }
    }

    suspend fun loadMeta(ref: MediaRef): LoadMetaResult {
        val adapter = adaptersBySourceId[ref.source.id]
            ?: error("Unknown source: ${ref.source.id}")
        return adapter.loadMeta(ref.mediaId)
    }

    fun enqueue(ref: MediaRef, quality: Quality, targetPath: Path): DownloadHandle {
        val adapter = adaptersBySourceId[ref.source.id]
            ?: error("Unknown source: ${ref.source.id}")
        if (targetPath.parent == null) {
            error("targetPath must include a parent directory: $targetPath")
        }
        val startedAt = clock.now()
        val taskId = allocateTaskId(startedAt)
        val job = try {
            queue.submit { runDownload(taskId, adapter, ref.mediaId, quality, targetPath) }
        } catch (refused: IllegalStateException) {
            forget(taskId)
            throw refused
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                transition(taskId, DownloadStatus.Cancelled)
            }
        }
        return DownloadHandle(taskId, statesFor(taskId)) {
            transition(taskId, DownloadStatus.Cancelling)
            job.cancel()
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
        try {
            transition(taskId, DownloadStatus.LoadingMeta)
            // without a suspension point the phase is conflated away for a collector on another thread
            yield()
            Files.createFile(part)
            transition(taskId, DownloadStatus.Downloading)
            val outcome = adapter.download(mediaId, quality, part) { source ->
                updateProgress(taskId, source.toProgress())
            }
            if (outcome is DownloadResult.Failed) {
                deleteQuietly(part)
                transition(taskId, DownloadStatus.Failed(outcome.error))
                return
            }
            transition(taskId, DownloadStatus.Finalizing)
            // a cancel landing in this window would otherwise go unnoticed and the file would be moved anyway
            coroutineContext.ensureActive()
            Files.move(
                part,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            transition(taskId, DownloadStatus.Completed)
        } catch (cancellation: CancellationException) {
            deleteQuietly(part)
            throw cancellation
        } catch (failure: Exception) {
            deleteQuietly(part)
            transition(taskId, DownloadStatus.Failed(DownloadError.ExtractorBroken))
        }
    }

    private fun allocateTaskId(startedAt: Instant): TaskId {
        repeat(MAX_TASK_ID_ATTEMPTS) {
            val candidate = taskIdGenerator.next()
            if (register(candidate, startedAt)) return candidate
        }
        error("TaskId generator produced an occupied id $MAX_TASK_ID_ATTEMPTS times in a row")
    }

    private fun register(taskId: TaskId, startedAt: Instant): Boolean {
        val initial = DownloadState(taskId, DownloadStatus.Queued, startedAt = startedAt)
        while (true) {
            val current = states.value
            if (taskId in current) return false
            if (states.compareAndSet(current, current + (taskId to initial))) return true
        }
    }

    private fun forget(taskId: TaskId) {
        states.update { it - taskId }
    }

    private fun transition(taskId: TaskId, status: DownloadStatus) {
        states.update { states ->
            val current = states[taskId] ?: return@update states
            if (current.status.isTerminal) return@update states
            states + (taskId to current.copy(
                status = status,
                finishedAt = if (status.isTerminal) clock.now() else current.finishedAt,
            ))
        }
    }

    private fun updateProgress(taskId: TaskId, progress: Progress) {
        states.update { states ->
            val current = states[taskId] ?: return@update states
            states + (taskId to current.copy(progress = progress))
        }
    }

    private fun statesFor(taskId: TaskId): Flow<DownloadState> = states.mapNotNull { it[taskId] }

    private fun partialFilePath(target: Path, taskId: TaskId): Path =
        target.resolveSibling("${target.fileName}.part-$taskId")

    private fun deleteQuietly(part: Path) {
        try {
            Files.deleteIfExists(part)
        } catch (_: Exception) {
        }
    }
}

private const val MAX_TASK_ID_ATTEMPTS = 16

private const val FRACTION_SCALE = 1000L

private val DownloadStatus.isTerminal: Boolean
    get() = this is DownloadStatus.Completed || this is DownloadStatus.Cancelled || this is DownloadStatus.Failed

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
