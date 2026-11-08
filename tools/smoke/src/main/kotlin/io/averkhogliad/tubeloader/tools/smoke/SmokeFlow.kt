package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.MediaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind

private val QUALITY_1080P = Quality("1080p", TrackKind.Video, "1080p")

/** The tool of the delegate adapter is missing on the machine: the target is skipped, the run goes on. */
private const val YT_DLP_MISSING = "yt-dlp is not on the machine, set download.youtube.binary-path to the binary"

internal data class DownloadPlan(val ref: MediaRef, val meta: MediaMeta)

internal suspend fun runOne(session: SmokeSession, target: SmokeTarget) {
    val plan = planDownload(session, target) ?: return
    val name = targetName(target, plan.ref.mediaId)
    // a delegate adapter hands the path of ffmpeg to the tool it delegates to, so the binary has to
    // be on the disk before that tool starts
    val ffmpeg = session.tools.ensureFfmpeg()
    if (ffmpeg.isFailure) {
        session.dashboard.note("$name: skipped, ffmpeg is not available")
        return
    }
    val safeName = sanitize(name)
    val path = session.targetDir.resolve("$safeName.mp4")
    val handle = session.useCases.enqueueDownload.enqueue(plan.ref, pickQuality(plan.meta), path)
    session.observer.register(handle.taskId, DownloadEntry(name, safeName))
    session.observer.awaitTerminal(handle.taskId)
}

/**
 * The name of the target: the one the command gave, or the media id the source answered with, or
 * the url while the source has not answered yet.
 *
 * The source knows its own ids, so the file is named after the media and not after the url — the id
 * of a video on YouTube is in the query of the link, not in its path.
 */
internal fun targetName(target: SmokeTarget, mediaId: String? = null): String =
    when (target) {
        is SmokeTarget.ByUrl -> mediaId ?: target.url
        is SmokeTarget.ById -> target.name
    }

private suspend fun planDownload(session: SmokeSession, target: SmokeTarget): DownloadPlan? {
    // the tool of the machine is checked before the resolution when the target names its source: the
    // resolution of a youtube target runs the tool, so a missing tool would look like a broken source
    if (target is SmokeTarget.ById && target.code == YOUTUBE_CODE && !session.tools.ytDlpAvailable()) {
        session.dashboard.note("${target.name}: skipped, $YT_DLP_MISSING")
        return null
    }
    return when (val resolved = resolve(session, target)) {
        is MediaResult.Found -> {
            DownloadPlan(resolved.ref, resolved.meta)
        }

        MediaResult.NotFound -> {
            session.dashboard.note("${targetName(target)}: skipped, the source has no such media")
            null
        }

        MediaResult.Unsupported -> {
            session.dashboard.note("${targetName(target)}: skipped, no adapter claims the target")
            null
        }

        is MediaResult.Failed -> {
            session.dashboard.note("${targetName(target)}: skipped, ${failureOf(session, resolved.error)}")
            null
        }
    }
}

/**
 * The media item of the target: by url the source is the one that claims it, by id the source is
 * named by its code.
 */
private suspend fun resolve(session: SmokeSession, target: SmokeTarget): MediaResult =
    when (target) {
        is SmokeTarget.ByUrl -> session.useCases.resolveMedia.byUrl(target.url)
        is SmokeTarget.ById -> session.useCases.resolveMedia.byId(target.code, target.mediaId)
    }

/**
 * The reason of a failed resolution: the tool of the machine is named when it is missing, because a
 * target of a delegate source cannot be read without it.
 */
private fun failureOf(session: SmokeSession, error: DownloadError): String =
    if (session.tools.ytDlpAvailable()) {
        "the source failed: $error"
    } else {
        "the source failed: $error ($YT_DLP_MISSING)"
    }

private fun pickQuality(meta: MediaMeta): Quality =
    meta.qualities.firstOrNull { it.kind == TrackKind.Video } ?: QUALITY_1080P

private fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
