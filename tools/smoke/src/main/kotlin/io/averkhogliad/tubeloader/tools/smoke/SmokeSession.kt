package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.usecase.DownloadSession
import io.averkhogliad.tubeloader.core.usecase.ResolveMedia
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The session of one smoke run: the use cases of the run and the table they feed.
 *
 * The stop of the queue is passed in by the composition: owning the queue is not the business of
 * the use-case layer.
 */
internal class SmokeSession(
    facade: CoreFacade,
    val targetDir: Path,
    val dashboard: ProgressDashboard,
    val tools: SmokeTools,
    private val stop: suspend (Duration) -> Boolean,
) {

    val resolveMedia = ResolveMedia(facade)
    val downloadSession = DownloadSession(facade)
    val observer = DownloadStateObserver(downloadSession, dashboard)

    suspend fun shutdown(timeout: Duration): Boolean = stop(timeout)
}
