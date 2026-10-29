package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.TaskId

class CancelDownload(private val tracker: DownloadTracker) {

    fun cancel(taskId: TaskId): Boolean {
        val handle = tracker.handle(taskId) ?: return false
        handle.cancel()
        return true
    }
}
