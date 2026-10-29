package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.download.DownloadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

private val stateOrder: Comparator<DownloadState> = compareBy({ it.startedAt }, { it.taskId.value })

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDownloads(private val tracker: DownloadTracker) {

    fun states(): Flow<List<DownloadState>> =
        tracker.handles.flatMapLatest { handles ->
            if (handles.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(handles.map { it.state }) { states -> states.sortedWith(stateOrder) }
            }
        }
}
