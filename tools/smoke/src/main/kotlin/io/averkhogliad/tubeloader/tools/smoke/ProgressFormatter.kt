package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.fraction
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import java.util.Locale
import kotlin.time.Duration

fun formatProgress(state: DownloadState, name: String): String =
    when (state.status) {
        DownloadStatus.Downloading -> "[${state.taskId}] $name: downloading ${shareOf(state.progress)}"
        DownloadStatus.Queued -> "[${state.taskId}] $name: queued"
        else -> ""
    }

fun formatTerminal(state: DownloadState, name: String, targetName: String? = null): String {
    val tag = state.taskId.toString()
    return when (val status = state.status) {
        DownloadStatus.Completed -> {
            val tail = finishedTail(state, targetName)
            "[$tag] $name: done${tail?.let { " ($it)" }.orEmpty()}"
        }

        DownloadStatus.Cancelled -> {
            "[$tag] $name: cancelled"
        }

        is DownloadStatus.Failed -> {
            val cause = status.cause?.message?.takeIf { it.isNotBlank() }
            val causePart = cause?.let { ": $it" }.orEmpty()
            "[$tag] $name: FAILED ${status.error::class.simpleName}$causePart"
        }

        else -> {
            "[$tag] $name: unexpected terminal ${status::class.simpleName}"
        }
    }
}

private fun shareOf(progress: Progress): String {
    val share = progress.fraction() ?: return "-"
    return "${percent(share)}%"
}

@Suppress("MagicNumber")
private fun percent(share: Double): String = String.format(Locale.ROOT, "%.2f", share * 100)

private fun finishedTail(state: DownloadState, targetName: String?): String? {
    val finished = state.finishedAt ?: return targetName
    val elapsed = finished - state.startedAt
    return if (targetName == null) elapsed.secondsLabel() else "$targetName, ${elapsed.secondsLabel()}"
}

private fun Duration.secondsLabel(): String = "${inWholeSeconds}s"
