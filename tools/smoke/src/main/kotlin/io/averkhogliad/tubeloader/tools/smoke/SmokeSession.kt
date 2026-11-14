package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.app.TubeloaderApp
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The session of one smoke run: the graph of the application and the table it feeds.
 *
 * The stop of the queue is delegated to the composition root: owning the queue is not the business
 * of the tool.
 */
internal class SmokeSession(
    val app: TubeloaderApp,
    val targetDir: Path,
    val dashboard: ProgressDashboard,
) {

    val observer = DownloadStateObserver(app.downloads, dashboard)

    suspend fun shutdown(timeout: Duration): Boolean = app.shutdown(timeout)
}
