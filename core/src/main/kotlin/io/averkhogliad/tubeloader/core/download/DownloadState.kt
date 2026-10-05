package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.TaskId
import kotlin.time.Instant

data class DownloadState(
    val taskId: TaskId,
    val status: DownloadStatus,
    val progress: Progress = Progress.Indeterminate,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
)

sealed interface DownloadStatus {
    data object Queued : DownloadStatus

    data object LoadingMeta : DownloadStatus

    data object Downloading : DownloadStatus

    data object Finalizing : DownloadStatus

    data object Cancelling : DownloadStatus

    data object Completed : DownloadStatus

    data object Cancelled : DownloadStatus

    data class Failed(val error: DownloadError, val cause: Throwable? = null) : DownloadStatus
}
