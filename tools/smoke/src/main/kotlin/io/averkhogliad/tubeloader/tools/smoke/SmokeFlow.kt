package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind

private val QUALITY_1080P = Quality("1080p", TrackKind.Video, "1080p")

internal data class DownloadPlan(val ref: MediaRef, val meta: MediaMeta)

internal suspend fun runOne(session: SmokeSession, target: SmokeTarget) {
    val plan = planDownload(session, target) ?: return
    // a delegate adapter hands the path of ffmpeg to the tool it delegates to, so the binary has to
    // be on the disk before that tool starts
    session.tools.ensureFfmpeg()
    val name = targetName(target, plan.ref.mediaId)
    val safeName = sanitize(name)
    val path = session.targetDir.resolve("$safeName.mp4")
    val handle = session.useCases.enqueueDownload.enqueue(plan.ref, pickQuality(plan.meta), path)
    session.observer.register(handle.taskId, DownloadEntry(name, safeName))
    session.observer.awaitTerminal(handle.taskId)
}

/**
 * The name of the target: the one the command gave, or the media id the source answered with.
 *
 * The source knows its own ids, so the file is named after the media and not after the url — the id
 * of a video on YouTube is in the query of the link, not in its path.
 */
internal fun targetName(target: SmokeTarget, mediaId: String): String = target.name ?: mediaId

private suspend fun planDownload(session: SmokeSession, target: SmokeTarget): DownloadPlan? =
    when (val resolved = session.useCases.resolveUrl.byUrl(target.url)) {
        is ResolveResult.Resolved -> {
            loadPlan(session, target, resolved.ref)
        }

        ResolveResult.NotFound -> {
            session.dashboard.note("${labelOf(target)}: skipped, source says NotFound")
            null
        }

        ResolveResult.Unsupported -> {
            session.dashboard.note("${labelOf(target)}: skipped, URL not supported by any adapter")
            null
        }
    }

private suspend fun loadPlan(session: SmokeSession, target: SmokeTarget, ref: MediaRef): DownloadPlan? =
    when (val loaded = session.useCases.loadMedia.load(ref)) {
        is LoadMetaResult.Found -> {
            DownloadPlan(ref, loaded.meta)
        }

        LoadMetaResult.NotFound -> {
            session.dashboard.note("${targetName(target, ref.mediaId)}: skipped, metadata not found")
            null
        }

        is LoadMetaResult.Failed -> {
            session.dashboard.note("${targetName(target, ref.mediaId)}: skipped, metadata failed: ${loaded.error}")
            null
        }
    }

/**
 * The label of a target before its source answered: the name the command gave, or the url itself.
 * The media id is not known yet — only the source that owns it can tell it.
 */
private fun labelOf(target: SmokeTarget): String = target.name ?: target.url

private fun pickQuality(meta: MediaMeta): Quality =
    meta.qualities.firstOrNull { it.kind == TrackKind.Video } ?: QUALITY_1080P

internal fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
