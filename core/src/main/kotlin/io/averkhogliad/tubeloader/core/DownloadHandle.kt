package io.averkhogliad.tubeloader.core

import kotlinx.coroutines.flow.Flow

class DownloadHandle(val taskId: TaskId, val state: Flow<DownloadState>, private val onCancel: () -> Unit) {
    fun cancel() = onCancel()
}
