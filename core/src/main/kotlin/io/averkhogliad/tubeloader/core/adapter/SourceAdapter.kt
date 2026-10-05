package io.averkhogliad.tubeloader.core.adapter

import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import java.nio.file.Path

enum class DownloadCapability { Delegate, Native, ResolveOnly }

sealed interface FindResult {
    data class Found(val mediaId: String) : FindResult

    data object Unsupported : FindResult

    data object NotFound : FindResult
}

sealed interface LoadMetaResult {
    data class Found(val meta: MediaMeta) : LoadMetaResult

    data object NotFound : LoadMetaResult
}

interface SourceAdapter {
    val capability: DownloadCapability

    val displayName: String

    suspend fun find(input: String): FindResult

    suspend fun loadMeta(id: String): LoadMetaResult

    suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult
}
