package io.averkhogliad.tubeloader.core.adapter

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.download.DownloadRequest
import java.nio.file.Path

class FakeSourceAdapter(
    override val displayName: String = "fake",
    override val capability: DownloadCapability = DownloadCapability.Delegate,
) : SourceAdapter {

    var onFind: suspend (String) -> FindResult = { FindResult.Unsupported }
    var onLoadMeta: suspend (String) -> LoadMetaResult = { LoadMetaResult.NotFound }
    var onDownload: suspend (
        DownloadRequest,
        (SourceProgress) -> Unit,
    ) -> DownloadResult = { _, _ -> DownloadResult.Success }

    val downloaded = mutableListOf<DownloadRequest>()

    override suspend fun find(input: String): FindResult = onFind(input)

    override suspend fun loadMeta(id: String): LoadMetaResult = onLoadMeta(id)

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult = recordAndDownload(DownloadRequest(mediaId, quality, targetPath), onProgress)

    private suspend fun recordAndDownload(
        request: DownloadRequest,
        onProgress: (SourceProgress) -> Unit,
    ): DownloadResult {
        downloaded += request
        return onDownload(request, onProgress)
    }
}
