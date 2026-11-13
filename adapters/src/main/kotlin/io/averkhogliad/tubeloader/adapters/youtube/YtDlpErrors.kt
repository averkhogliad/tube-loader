package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.domain.DownloadError

/** The quality the adapter asked for is not something the tool can serve: our own translation is wrong. */
private val OPTION_SIGNS =
    listOf(
        "Requested format is not available",
        "no such option",
        "unrecognized arguments",
        "Unsupported option",
    )

/** The source does not let this machine in at all, whatever it asks next. */
private val ANTIBOT_SIGNS = listOf("not a bot")

/** The address the tool was given is no longer honoured, so it has to be taken again. */
private val EXPIRED_SIGNS =
    listOf(
        "HTTP Error 403",
        "HTTP Error 410",
        "url expired",
    )

/** The media is gone from the source: private, removed, or never published under that id. */
private val GONE_SIGNS =
    listOf(
        "Video unavailable",
        "Private video",
        "This video is private",
        "has been removed",
        "does not exist",
    )

/** The source could not be reached this time, so the same call may well answer on the next one. */
private val TRANSIENT_SIGNS =
    listOf(
        "Unable to download webpage",
        "timed out",
        "TimeoutError",
        "Connection reset",
        "Connection refused",
        "Temporary failure",
        "urlopen error",
        "HTTP Error 408",
        "HTTP Error 429",
        "HTTP Error 5",
    )

/**
 * The signs in the order they are read: a precise one comes before a general one, so a failure that
 * names both an expired address and an unreachable page is read as the expired address.
 */
private val SIGNS: List<Pair<List<String>, DownloadError>> =
    listOf(
        OPTION_SIGNS to DownloadError.ExtractorBroken,
        ANTIBOT_SIGNS to DownloadError.AntiBotBlocked,
        EXPIRED_SIGNS to DownloadError.UrlExpired,
        GONE_SIGNS to DownloadError.NotFound,
        TRANSIENT_SIGNS to DownloadError.NetworkTransient,
    )

/**
 * Classifies a failed call of the tool by what it said, not by the code it ended with: the tool
 * names its own failures on the error stream, and the same code carries unrelated reasons.
 */
internal fun downloadErrorOf(failure: Throwable): DownloadError =
    when (failure) {
        is YtDlpFailed -> errorOf(failure.toolMessage)

        is YtDlpTimedOut -> DownloadError.NetworkTransient

        // a call that never reached the tool — the binary is missing or the process would not start
        else -> DownloadError.ExtractorBroken
    }

private fun errorOf(message: String): DownloadError =
    SIGNS
        .firstOrNull { (signs, _) -> signs.any { sign -> message.contains(sign, ignoreCase = true) } }
        ?.second
        ?: DownloadError.ExtractorBroken
