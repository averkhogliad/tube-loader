package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DownloadTracker {

    private val registered = MutableStateFlow<List<DownloadHandle>>(emptyList())

    val handles: StateFlow<List<DownloadHandle>> = registered.asStateFlow()

    fun register(handle: DownloadHandle) {
        registered.update { current -> current + handle }
    }

    fun handle(taskId: TaskId): DownloadHandle? = registered.value.lastOrNull { it.taskId == taskId }
}
