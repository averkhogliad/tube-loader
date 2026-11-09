package io.averkhogliad.tubeloader.core.adapter

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.download.DownloadRequest
import java.nio.file.Path

class FakeSourceAdapter(
    val code: String = "fake",
    override val displayName: String = code,
    override val capability: DownloadCapability = DownloadCapability.Delegate,
) : SourceAdapter {

    var onResolveId: suspend (String) -> ResolveIdResult = { ResolveIdResult.Unsupported }
    var onFindById: suspend (String) -> LoadMetaResult = { LoadMetaResult.NotFound }
    var onDownload: suspend (
        DownloadRequest,
        (Progress) -> Unit,
    ) -> DownloadResult = { _, _ -> DownloadResult.Success }

    val downloaded = mutableListOf<DownloadRequest>()

    override suspend fun resolveId(input: String): ResolveIdResult = onResolveId(input)

    override suspend fun findById(id: String): LoadMetaResult = onFindById(id)

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (Progress) -> Unit,
    ): DownloadResult = recordAndDownload(DownloadRequest(mediaId, quality, targetPath), onProgress)

    private suspend fun recordAndDownload(request: DownloadRequest, onProgress: (Progress) -> Unit): DownloadResult {
        downloaded += request
        return onDownload(request, onProgress)
    }
}
