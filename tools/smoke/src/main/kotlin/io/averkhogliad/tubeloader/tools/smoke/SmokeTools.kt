package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.media.ensureManagedFfmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The external tools the sources need on the disk.
 *
 * The media tool asks for ffmpeg by itself, but the delegate adapter hands the path of the binary to
 * another tool, and that tool starts before the binary is there. So the binary is fetched once per
 * run, before the first download starts.
 *
 * The binary of the delegate tool itself is not here: the adapter fetches it on its first call.
 *
 * A tool that cannot be obtained is a note, not a crash: the run goes on, and every target that
 * needs the tool is skipped.
 */
internal class SmokeTools(
    paths: ToolPaths,
    private val dashboard: ProgressDashboard,
    private val ensure: () -> Result<Path> = { ensureManagedFfmpeg(paths) },
) {

    private val mutex = Mutex()
    private var ffmpeg: Result<Path>? = null

    suspend fun ensureFfmpeg(): Result<Path> =
        mutex.withLock {
            ffmpeg
                ?: withContext(Dispatchers.IO) { ensure() }
                    .onFailure { failure -> dashboard.note("ffmpeg is not available: ${failure.message}") }
                    .also { fetched -> ffmpeg = fetched }
        }
}
