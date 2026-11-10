package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.adapters.youtube.ensureManagedYtDlp
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.media.ensureManagedFfmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The external tools the sources need on the disk, fetched once at the startup of the run.
 *
 * Both tools are prepared before the first target starts: a target then waits for its source and
 * not for the network. The media tool asks for ffmpeg by itself, but the delegate adapter hands the
 * path of the binary to another tool, and that tool starts before the binary is there. The binary
 * of the delegate tool itself the adapter fetches on its first call; fetching it here simply keeps
 * that call off the network.
 *
 * A tool that cannot be obtained is a note, not a crash: the run goes on, and every target that
 * needs the tool fails or skips on its own.
 */
internal class SmokeTools(
    paths: ToolPaths,
    private val dashboard: ProgressDashboard,
    private val ytDlpFetch: suspend () -> Result<Path> = { ensureManagedYtDlp(paths) },
    private val ensure: () -> Result<Path> = { ensureManagedFfmpeg(paths) },
) {

    private val mutex = Mutex()
    private var ffmpeg: Result<Path>? = null
    private var ytDlp: Result<Path>? = null

    suspend fun ensureFfmpeg(): Result<Path> =
        mutex.withLock {
            ffmpeg
                ?: withContext(Dispatchers.IO) { ensure() }
                    .onFailure { failure -> dashboard.note("ffmpeg is not available: ${failure.message}") }
                    .also { fetched -> ffmpeg = fetched }
        }

    suspend fun ensureYtDlp(): Result<Path> =
        mutex.withLock {
            ytDlp
                ?: ytDlpFetch()
                    .onFailure { failure -> dashboard.note("yt-dlp is not available: ${failure.message}") }
                    .also { fetched -> ytDlp = fetched }
        }
}
