package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.facade.MediaResult

private const val FALLBACK_VIDEO_HEIGHT = 1080

private val QUALITY_1080P = Quality.ofHeight(FALLBACK_VIDEO_HEIGHT)

internal data class DownloadPlan(val ref: MediaRef, val meta: MediaMeta)

/**
 * The rows of the run, in the order of the command: every target is on the table from the start,
 * so the run always shows what it is going to download and how each download is doing.
 */
internal fun targetRows(targets: List<SmokeTarget>): List<TargetRow> {
    val names = targets.map(::targetName)
    val width = nameWidth(names)
    return targets.mapIndexed { index, _ ->
        val name = names[index]
        TargetRow(key = index.toString(), name = name, fileName = "${sanitize(name)}.mp4", width = width)
    }
}

internal suspend fun runOne(session: SmokeSession, target: SmokeTarget, row: TargetRow) {
    // the row answers while the media is being read from the source: a silent resolve looks dead
    session.dashboard.update(row.key, formatResolving(row))
    val plan = planDownload(session, target, row) ?: return
    // the tools are fetched at the startup of the run: this call only answers the cached result,
    // and the binary has to be on the disk before the tool of the adapter starts
    val ffmpeg = session.tools.ensureFfmpeg()
    if (ffmpeg.isFailure) {
        session.dashboard.update(row.key, formatSkipped(row, "ffmpeg is not available"))
        return
    }
    val path = session.targetDir.resolve(row.fileName)
    val handle = session.downloadSession.enqueue(plan.ref, pickQuality(plan.meta), path)
    session.observer.register(handle.taskId, row)
    session.observer.awaitTerminal(handle.taskId)
}

/**
 * The name of the target: the one the command gave — every target carries one of its own.
 */
internal fun targetName(target: SmokeTarget): String = target.name

/**
 * The plan of the target, or null when it cannot be downloaded: a target the run skips says why in
 * its own row of the table. A source the tool of which is not available answers with a failed
 * resolution, so the skip names the source and not a missing binary: the binary is the business of
 * the adapter alone.
 */
private suspend fun planDownload(session: SmokeSession, target: SmokeTarget, row: TargetRow): DownloadPlan? =
    when (val resolved = resolve(session, target)) {
        is MediaResult.Found -> {
            DownloadPlan(resolved.ref, resolved.meta)
        }

        MediaResult.NotFound -> {
            skip(session, row, "the source has no such media")
            null
        }

        MediaResult.Unsupported -> {
            skip(session, row, "no adapter claims the target")
            null
        }

        is MediaResult.Failed -> {
            skip(session, row, "the source failed: ${resolved.error}")
            null
        }
    }

private fun skip(session: SmokeSession, row: TargetRow, reason: String) {
    session.dashboard.update(row.key, formatSkipped(row, reason))
}

/**
 * The media item of the target: by url the source is the one that claims it, by id the source is
 * named by its code.
 */
private suspend fun resolve(session: SmokeSession, target: SmokeTarget): MediaResult =
    when (target) {
        is SmokeTarget.ByUrl -> session.resolveMedia.byUrl(target.url)
        is SmokeTarget.ById -> session.resolveMedia.byId(target.code, target.mediaId)
    }

private fun pickQuality(meta: MediaMeta): Quality =
    meta.qualities.firstOrNull { it.kind == TrackKind.Video } ?: QUALITY_1080P

private fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
