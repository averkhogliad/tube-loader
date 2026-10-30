package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class DownloadTracker {

    private val registered = MutableStateFlow<Map<TaskId, DownloadHandle>>(emptyMap())

    internal val handles: Flow<List<DownloadHandle>> = registered.map { it.values.toList() }

    internal fun register(handle: DownloadHandle) {
        registered.update { current ->
            LinkedHashMap(current).apply { put(handle.taskId, handle) }
        }
    }

    internal fun handle(taskId: TaskId): DownloadHandle? = registered.value[taskId]
}
