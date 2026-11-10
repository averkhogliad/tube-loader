package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.fraction
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import java.util.Locale

private const val PLANNED = "planned"

/** The width of the name column: the longest name of the run. */
internal fun nameWidth(names: List<String>): Int = names.maxOfOrNull(String::length) ?: 0

/** The row of a target that has not started yet. */
internal fun formatPlanned(row: TargetRow): String = rowOf(row, PLANNED)

/** The row of a target whose media and tools are being prepared before the download starts. */
internal fun formatResolving(row: TargetRow): String = rowOf(row, "resolving")

/** The row of a target the run skipped before the download started. */
internal fun formatSkipped(row: TargetRow, reason: String): String = rowOf(row, "skipped: $reason")

/** The row of a target by the state of its download: the phase, the progress and the terminal outcome. */
internal fun formatRow(state: DownloadState, row: TargetRow): String = rowOf(row, statusOf(state, row))

private fun rowOf(row: TargetRow, status: String): String = "${row.name.padEnd(row.width)}  $status"

private fun statusOf(state: DownloadState, row: TargetRow): String =
    when (val status = state.status) {
        DownloadStatus.Queued -> "queued"
        DownloadStatus.LoadingMeta -> "loading metadata"
        DownloadStatus.Downloading -> "downloading ${shareOf(state.progress)}"
        DownloadStatus.Finalizing -> "finalizing"
        DownloadStatus.Cancelling -> "cancelling"
        DownloadStatus.Completed -> "done (${finishedTail(state, row.fileName)})"
        DownloadStatus.Cancelled -> "cancelled"
        is DownloadStatus.Failed -> failed(status)
    }

private fun failed(status: DownloadStatus.Failed): String {
    val cause = status.cause?.message?.takeIf { it.isNotBlank() }
    val causePart = cause?.let { ": $it" }.orEmpty()
    return "failed: ${status.error::class.simpleName}$causePart"
}

@Suppress("MagicNumber")
private fun shareOf(progress: Progress): String {
    val share = progress.fraction() ?: return "-"
    return "${String.format(Locale.ROOT, "%.2f", share * 100)}%"
}

private fun finishedTail(state: DownloadState, fileName: String): String {
    val finished = state.finishedAt ?: return fileName
    val elapsed = finished - state.startedAt
    return "$fileName, ${elapsed.inWholeSeconds}s"
}
