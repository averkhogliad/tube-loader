package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import java.nio.file.Path

class EnqueueDownload(private val facade: CoreFacade, private val tracker: DownloadTracker) {

    fun enqueue(ref: MediaRef, quality: Quality, targetPath: Path): DownloadHandle {
        val handle = facade.enqueue(ref, quality, targetPath)
        tracker.register(handle)
        return handle
    }
}
