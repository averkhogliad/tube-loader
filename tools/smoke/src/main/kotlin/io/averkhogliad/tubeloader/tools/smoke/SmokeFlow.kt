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
    val safeName = sanitize(target.name)
    val path = session.targetDir.resolve("$safeName.mp4")
    val handle = session.useCases.enqueueDownload.enqueue(plan.ref, pickQuality(plan.meta), path)
    session.observer.register(handle.taskId, DownloadEntry(target.name, safeName))
    session.observer.awaitTerminal(handle.taskId)
}

private suspend fun planDownload(session: SmokeSession, target: SmokeTarget): DownloadPlan? =
    when (val resolved = session.useCases.resolveUrl.byUrl(target.url)) {
        is ResolveResult.Resolved -> {
            loadPlan(session, target, resolved.ref)
        }

        ResolveResult.NotFound -> {
            session.dashboard.note("${target.name}: skipped, source says NotFound")
            null
        }

        ResolveResult.Unsupported -> {
            session.dashboard.note("${target.name}: skipped, URL not supported by any adapter")
            null
        }
    }

private suspend fun loadPlan(session: SmokeSession, target: SmokeTarget, ref: MediaRef): DownloadPlan? =
    when (val loaded = session.useCases.loadMedia.load(ref)) {
        is LoadMetaResult.Found -> {
            DownloadPlan(ref, loaded.meta)
        }

        LoadMetaResult.NotFound -> {
            session.dashboard.note("${target.name}: skipped, metadata not found")
            null
        }

        is LoadMetaResult.Failed -> {
            session.dashboard.note("${target.name}: skipped, metadata failed: ${loaded.error}")
            null
        }
    }

private fun pickQuality(meta: MediaMeta): Quality =
    meta.qualities.firstOrNull { it.kind == TrackKind.Video } ?: QUALITY_1080P

private fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
