package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.retry.retry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

private val WHITESPACE = Regex("""\s+""")

/** A progress line of the template, or `Indeterminate` while the tool cannot name the amount of the work. */
internal fun progressOf(line: String): Progress? {
    val text = line.trimStart()
    if (!text.startsWith(PROGRESS_MARK)) return null
    val amounts = text.removePrefix(PROGRESS_MARK).trim().split(WHITESPACE)
    val processed = amounts.getOrNull(0)?.toLongOrNull()
    val total = amounts.getOrNull(1)?.toLongOrNull()
    return if (processed == null || total == null) Progress.Indeterminate else Progress.Absolute(processed, total)
}

/** The path the transfer ended at, or null when the line names none. */
internal fun producedPathOf(line: String): Path? =
    line
        .trim()
        .takeIf { text -> text.startsWith(FILEPATH_MARK) }
        ?.removePrefix(FILEPATH_MARK)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(Path::of)

/**
 * A directory of its own for one call: the parts and the temporary files of the merge land there, not
 * in the directory the application runs from nor next to the file the user asked for.
 */
internal fun stagingDirectoryOf(settings: YtDlpConfig, mediaId: String): Path {
    val root = settings.workDirectory
    Files.createDirectories(root)
    return Files.createTempDirectory(root, "$mediaId-")
}

/** Whatever a call left behind goes away with its staging: an interrupted merge leaves parts behind. */
internal fun Path.removeTree() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
    }
}

/**
 * Moves the file of the tool onto the part path of the core. The rename answers first, because a
 * staging under the home directory and a target on another drive are the same case otherwise.
 */
internal fun place(from: Path, to: Path) {
    try {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: IOException) {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        Files.deleteIfExists(from)
    }
}

/** Passes each step of the tool down once, so the amount of the work never goes back for the consumer. */
internal class ProgressFeed(private val sink: (Progress) -> Unit) {

    private var last: Progress? = null
    private var processed = -1L

    fun feed(next: Progress) {
        if (next == last) return
        if (next is Progress.Absolute) {
            if (next.processed < processed) return
            processed = next.processed
        }
        last = next
        sink(next)
    }

    /**
     * The end of a transfer is a fact of its own: the amount the tool reported counts a stream it
     * pulled, not the file it left behind, so the completion is passed down whatever came before.
     */
    fun complete(size: Long) {
        val done = Progress.Absolute(size, size)
        processed = size
        last = done
        sink(done)
    }
}

/**
 * Runs the transfer the command describes and hands the file the tool produced to the core. The path
 * of the file comes from its own line: a run that names none made no file.
 */
internal suspend fun transfer(
    runner: YtDlpProcessRunner,
    command: List<String>,
    settings: YtDlpConfig,
    targetPath: Path,
    onProgress: (Progress) -> Unit,
): DownloadResult {
    val feed = ProgressFeed(onProgress)
    feed.feed(Progress.Indeterminate)
    var produced: Path? = null
    val result =
        retry(policyOf(settings)) {
            runner.run(command) { line ->
                producedPathOf(line)?.let { produced = it }
                progressOf(line)?.let(feed::feed)
            }
        }
    val failure = result.exceptionOrNull()
    return when {
        failure != null -> DownloadResult.Failed(downloadErrorOf(failure))
        produced == null || !Files.isRegularFile(produced) -> DownloadResult.Failed(DownloadError.ExtractorBroken)
        else -> placeIn(produced, targetPath, feed)
    }
}

private fun placeIn(produced: Path, targetPath: Path, feed: ProgressFeed): DownloadResult =
    try {
        place(produced, targetPath)
        // the core reads the completion of a download as an absolute update, not a fraction
        feed.complete(Files.size(targetPath))
        DownloadResult.Success
    } catch (_: IOException) {
        DownloadResult.Failed(DownloadError.ExtractorBroken)
    }
