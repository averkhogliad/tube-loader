package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.usecase.CancelDownload
import io.averkhogliad.tubeloader.core.usecase.DownloadTracker
import io.averkhogliad.tubeloader.core.usecase.EnqueueDownload
import io.averkhogliad.tubeloader.core.usecase.LoadMedia
import io.averkhogliad.tubeloader.core.usecase.ObserveDownloads
import io.averkhogliad.tubeloader.core.usecase.ResolveUrl
import io.averkhogliad.tubeloader.core.usecase.UseCaseShutdown
import java.nio.file.Path
import kotlin.time.Duration

internal class UseCases(facade: CoreFacade, shutdownRequest: suspend (Duration) -> Boolean) {

    private val tracker = DownloadTracker()

    val resolveUrl = ResolveUrl(facade)
    val loadMedia = LoadMedia(facade)
    val enqueueDownload = EnqueueDownload(facade, tracker)
    val cancelDownload = CancelDownload(tracker)
    val observeDownloads = ObserveDownloads(tracker)
    val shutdown = UseCaseShutdown(shutdownRequest)
}

internal class SmokeSession(
    val targetDir: Path,
    val dashboard: ProgressDashboard,
    val useCases: UseCases,
    val observer: DownloadStateObserver,
    val tools: SmokeTools,
) {

    suspend fun shutdown(timeout: Duration): Boolean = useCases.shutdown.shutdown(timeout)
}
