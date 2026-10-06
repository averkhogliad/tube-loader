package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.TaskId
import kotlinx.coroutines.flow.Flow

class DownloadHandle(
    val taskId: TaskId,
    val state: Flow<DownloadState>,
    private val onCancel: () -> Unit,
) {
    fun cancel() = onCancel()
}
