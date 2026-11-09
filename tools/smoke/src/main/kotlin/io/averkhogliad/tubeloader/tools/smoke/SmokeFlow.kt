package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.MediaResult
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind

private val QUALITY_1080P = Quality("1080p", TrackKind.Video, "1080p")

internal data class DownloadPlan(val ref: MediaRef, val meta: MediaMeta)

internal suspend fun runOne(session: SmokeSession, target: SmokeTarget) {
    val plan = planDownload(session, target) ?: return
    val name = targetName(target)
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

/** The name of the target: the one the command gave — every target carries one of its own. */
internal fun targetName(target: SmokeTarget): String = target.name

/**
 * The plan of the target, or null when it cannot be downloaded. A source the tool of which is not
 * available answers with a failed resolution, so the skip names the source and not a missing binary:
 * the binary is the business of the adapter alone.
 */
private suspend fun planDownload(session: SmokeSession, target: SmokeTarget): DownloadPlan? =
    when (val resolved = resolve(session, target)) {
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
            session.dashboard.note("${targetName(target)}: skipped, the source failed: ${resolved.error}")
            null
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

private fun pickQuality(meta: MediaMeta): Quality =
    meta.qualities.firstOrNull { it.kind == TrackKind.Video } ?: QUALITY_1080P

private fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
