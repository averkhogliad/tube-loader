package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.usecase.CancelDownload
import io.averkhogliad.tubeloader.core.usecase.DownloadTracker
import io.averkhogliad.tubeloader.core.usecase.EnqueueDownload
import io.averkhogliad.tubeloader.core.usecase.LoadMedia
import io.averkhogliad.tubeloader.core.usecase.ObserveDownloads
import io.averkhogliad.tubeloader.core.usecase.ResolveUrl
import io.averkhogliad.tubeloader.core.usecase.UseCaseShutdown
import java.nio.file.Path

internal class UseCases(facade: CoreFacade, queue: DownloadQueue) {

    val tracker = DownloadTracker()
    val resolveUrl = ResolveUrl(facade)
    val loadMedia = LoadMedia(facade)
    val enqueueDownload = EnqueueDownload(facade, tracker)
    val cancelDownload = CancelDownload(tracker)
    val observeDownloads = ObserveDownloads(tracker)
    val shutdown = UseCaseShutdown(queue)
}

internal class SmokeSession(
    val targetDir: Path,
    val dashboard: ProgressDashboard,
    val useCases: UseCases,
    val observer: DownloadStateObserver,
)
