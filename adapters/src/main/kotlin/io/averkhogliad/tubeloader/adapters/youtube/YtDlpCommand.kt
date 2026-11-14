package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.Quality
import java.nio.file.Path

private const val WATCH_URL = "https://www.youtube.com/watch?v="

private const val NEWLINE = "--newline"
private const val NO_QUIET = "--no-quiet"
private const val NO_SIMULATE = "--no-simulate"
private const val PROGRESS = "--progress"
private const val PROGRESS_TEMPLATE = "--progress-template"
private const val PRINT = "--print"
private const val FORMAT = "-f"
private const val OUTPUT = "-o"
private const val FFMPEG_LOCATION = "--ffmpeg-location"
private const val CONCURRENT_FRAGMENTS = "--concurrent-fragments"

/** The mark a progress line carries: a line of the tool is told from the others by it alone. */
internal const val PROGRESS_MARK = "dl:@P "

/** The mark of the line naming the file the transfer ended at. */
internal const val FILEPATH_MARK = "fp="

private const val PROGRESS_TEMPLATE_VALUE = PROGRESS_MARK + "%(progress.downloaded_bytes)s %(progress.total_bytes)s"
private const val FILEPATH_TEMPLATE_VALUE = "after_move:" + FILEPATH_MARK + "%(filepath)s"
private const val STAGING_TEMPLATE = "%(id)s.%(ext)s"

internal fun watchUrlOf(id: String): String = WATCH_URL + id

/**
 * The transfer of [mediaId] into [staging]: the tool picks the format itself, merges video with its
 * audio through the bundled ffmpeg and reports both the progress and the path it ended at.
 *
 * [binary] is the absolute path of the tool the distribution handed out: the call does not depend on
 * the `PATH` of the machine nor on the directory the process runs from.
 */
internal fun downloadCommand(
    settings: YtDlpConfig,
    binary: Path,
    mediaId: String,
    selector: String,
    staging: Path,
): List<String> =
    listOf(
        binary.toString(),
        NEWLINE,
        NO_QUIET,
        NO_SIMULATE,
        PROGRESS,
        PROGRESS_TEMPLATE,
        PROGRESS_TEMPLATE_VALUE,
        PRINT,
        FILEPATH_TEMPLATE_VALUE,
        FORMAT,
        selector,
        OUTPUT,
        staging.resolve(STAGING_TEMPLATE).toString(),
        FFMPEG_LOCATION,
        settings.paths.ffmpegBinary.toString(),
        CONCURRENT_FRAGMENTS,
        settings.concurrentFragments.toString(),
        watchUrlOf(mediaId),
    )

/** The best video with its audio, no higher than the height the quality names. */
internal fun formatSelectorOf(quality: Quality): String? =
    quality.height?.let { height -> "bv*[height<=?$height]+ba/b[height<=?$height]" }
