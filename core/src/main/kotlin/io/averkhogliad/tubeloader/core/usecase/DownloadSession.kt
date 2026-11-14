package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.nio.file.Path

private val stateOrder: Comparator<DownloadState> = compareBy({ it.startedAt }, { it.taskId.value })

/**
 * The downloads of this run: queues them, cancels one by its task id and aggregates their states.
 *
 * The handles are private to the session; the caller sees the aggregated list only. Terminal
 * downloads stay in the list until an explicit removal, which the layer does not have yet.
 */
class DownloadSession(private val facade: CoreFacade) {

    private val registered = MutableStateFlow<Map<TaskId, DownloadHandle>>(emptyMap())

    fun enqueue(ref: MediaRef, quality: Quality, targetPath: Path): DownloadHandle {
        val handle = facade.enqueue(ref, quality, targetPath)
        registered.update { current -> LinkedHashMap(current).apply { put(handle.taskId, handle) } }
        return handle
    }

    /**
     * Cancels the download of the task id: `false` when the session knows no such download.
     */
    fun cancel(taskId: TaskId): Boolean {
        val handle = registered.value[taskId] ?: return false
        handle.cancel()
        return true
    }

    /**
     * The states of every download of the session, ordered by the moment it was queued.
     *
     * A new download re-subscribes the aggregation, so a handle registered after the observation
     * started shows up in the next emission.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun states(): Flow<List<DownloadState>> =
        registered.map { it.values.toList() }.flatMapLatest { handles ->
            if (handles.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(handles.map { it.state }) { states -> states.sortedWith(stateOrder) }
            }
        }
}
