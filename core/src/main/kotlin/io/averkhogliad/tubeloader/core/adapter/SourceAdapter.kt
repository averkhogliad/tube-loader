package io.averkhogliad.tubeloader.core.adapter

import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import java.nio.file.Path

enum class DownloadCapability {
    Delegate,
    Native,
    ResolveOnly,
}

sealed interface ResolveIdResult {

    data class Found(val mediaId: String) : ResolveIdResult

    /** The input does not belong to the source. */
    data object Unsupported : ResolveIdResult

    /** The source recognized its own url, but no media id came out of it. */
    data object NotFound : ResolveIdResult
}

sealed interface LoadMetaResult {

    data class Found(val meta: MediaMeta) : LoadMetaResult

    data object NotFound : LoadMetaResult

    data class Failed(val error: DownloadError) : LoadMetaResult
}

interface SourceAdapter {

    val capability: DownloadCapability

    val displayName: String

    /**
     * The media id of the url of the source, read without the network: `Unsupported` when the input
     * does not belong to the source, `NotFound` when the source recognized its own url but named no
     * media in it.
     */
    suspend fun resolveId(input: String): ResolveIdResult

    /**
     * The data of a media item of the source, read from the source itself: the id is the one the
     * source named, not a url to be parsed again.
     */
    suspend fun findById(id: String): LoadMetaResult

    suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (Progress) -> Unit,
    ): DownloadResult
}
