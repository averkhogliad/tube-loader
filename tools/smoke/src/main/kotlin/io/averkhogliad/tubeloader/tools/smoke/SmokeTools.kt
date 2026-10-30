package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.media.ensureManagedFfmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The name of the tool of the delegate adapter, as the machine knows it. */
private const val YT_DLP_BINARY = "yt-dlp"

/**
 * The external tools the sources need on the disk.
 *
 * The media tool asks for ffmpeg by itself, but a delegate adapter hands the path of the binary to
 * another tool, and that tool starts before the binary is there. So the binary is fetched once per
 * run, before the first download starts.
 *
 * A tool that cannot be obtained is a note, not a crash: the run goes on, and every target that
 * needs the tool is skipped.
 */
internal class SmokeTools(
    paths: ToolPaths,
    private val ytDlp: YtDlpConfig,
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

    /**
     * Whether the tool of the delegate adapter can be started: the binary the config names, or the
     * one the machine has on its `PATH`.
     *
     * The lookup touches the filesystem only: no process starts and no network is used.
     */
    fun ytDlpAvailable(): Boolean = ytDlpBinary() != null

    private fun ytDlpBinary(): Path? {
        val configured = ytDlp.binaryPath
        if (configured != null) return configured.takeIf { Files.isRegularFile(it) }
        return onPath(YT_DLP_BINARY)
    }

    private fun onPath(name: String): Path? {
        val path = System.getenv("PATH") ?: return null
        val directories = path.split(File.pathSeparator).filter { it.isNotBlank() }
        val candidates = executableNames(name)
        return directories
            .asSequence()
            .mapNotNull { directory -> runCatching { Path.of(directory) }.getOrNull() }
            .flatMap { folder -> candidates.asSequence().map { candidate -> folder.resolve(candidate) } }
            .firstOrNull { file -> Files.isRegularFile(file) && Files.isExecutable(file) }
    }

    /** The names the machine looks for: on Windows the extension of the executable is part of the name. */
    private fun executableNames(name: String): List<String> {
        if (!System.getProperty("os.name").lowercase().contains("win")) return listOf(name)
        val extensions = pathExtensions()
        return listOf(name) + extensions.map { extension -> name + extension.lowercase() }
    }

    /** The extensions the machine appends to a program name. */
    private fun pathExtensions(): List<String> {
        val declared = System.getenv("PATHEXT")
        val extensions = declared?.split(';')?.filter { it.isNotBlank() }
        return extensions?.takeIf { it.isNotEmpty() } ?: listOf(".EXE", ".CMD", ".BAT")
    }
}
