package io.averkhogliad.tubeloader.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

private const val FFMPEG = "ffmpeg"
private const val YT_DLP = "yt-dlp"

/**
 * Prepares the external tools of the application once and reports each of them separately.
 *
 * The instances of the distributions belong to the composition root, so one fetch per run is shared
 * by every call: a repeated call answers the status the first one prepared and does not repeat the
 * notes. A tool that cannot be obtained is a note, not a crash: the run goes on, and every call
 * that needs the tool fails or skips on its own.
 */
internal class ToolsPreparation(
    private val prepareFfmpeg: suspend () -> Result<Path>,
    private val prepareYtDlp: suspend () -> Result<Path>,
) {

    private val mutex = Mutex()
    private var prepared: PreparedTools? = null

    suspend fun prepare(onNote: (String) -> Unit): PreparedTools =
        mutex.withLock {
            prepared ?: prepareBoth(onNote).also { statuses -> prepared = statuses }
        }

    private suspend fun prepareBoth(onNote: (String) -> Unit): PreparedTools {
        val ffmpeg = statusOf(FFMPEG, prepareFfmpeg(), onNote)
        val ytDlp = statusOf(YT_DLP, prepareYtDlp(), onNote)
        return PreparedTools(ffmpeg, ytDlp)
    }

    private fun statusOf(tool: String, result: Result<Path>, onNote: (String) -> Unit): ToolStatus =
        result.fold(
            onSuccess = { binary -> ToolStatus.Ready(binary) },
            onFailure = { failure -> unavailable(tool, failure, onNote) },
        )

    private fun unavailable(tool: String, failure: Throwable, onNote: (String) -> Unit): ToolStatus {
        val reason = failure.message
        onNote(if (reason.isNullOrBlank()) "$tool is not available" else "$tool is not available: $reason")
        return ToolStatus.Unavailable(reason)
    }
}
