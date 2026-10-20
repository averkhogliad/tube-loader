package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.domain.DownloadError

/** The media is gone from the source: private, removed, or never published under that id. */
private val GONE_MARKS =
    listOf(
        "Video unavailable",
        "Private video",
        "has been removed",
        "does not exist",
        "is not available",
    )

/** The source could not be reached this time, so the same call may well answer on the next one. */
private val TRANSIENT_MARKS =
    listOf(
        "Unable to download webpage",
        "timed out",
        "Connection reset",
        "Temporary failure",
        "HTTP Error 5",
    )

/**
 * Classifies a failed call of the tool by what it said, not by the code it ended with: the tool
 * names its own failures on the error stream, and the same code carries unrelated reasons.
 */
internal fun downloadErrorOf(failure: Throwable): DownloadError =
    when (failure) {
        is YtDlpFailed -> errorOf(failure.toolMessage)
        else -> DownloadError.NetworkTransient
    }

private fun errorOf(message: String): DownloadError =
    when {
        GONE_MARKS.any { mark -> message.contains(mark, ignoreCase = true) } -> DownloadError.NotFound
        TRANSIENT_MARKS.any { mark -> message.contains(mark, ignoreCase = true) } -> DownloadError.NetworkTransient
        else -> DownloadError.ExtractorBroken
    }
