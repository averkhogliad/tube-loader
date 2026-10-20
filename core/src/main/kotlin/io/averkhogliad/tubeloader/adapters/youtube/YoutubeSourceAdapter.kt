package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import java.net.URI
import java.nio.file.Path

private const val SCHEME_SEPARATOR = "://"
private const val DEFAULT_SCHEME = "https://"
private const val ID_PARAM = "v"
private const val RESOLVE = "-J"
private const val SKIP_DOWNLOAD = "--skip-download"

private val YOUTUBE_HOST = Regex("""^(?:[a-z0-9-]+\.)*youtube\.com$""", RegexOption.IGNORE_CASE)
private val SHORT_HOST = Regex("""^(?:[a-z0-9-]+\.)*youtu\.be$""", RegexOption.IGNORE_CASE)
private val WATCH_PATH = Regex("""^/watch/?$""")
private val ID_PATH = Regex("""^/(?:shorts|embed)/([^/]+)/?$""")
private val MEDIA_ID = Regex("[A-Za-z0-9_-]+")

/**
 * Adapter over the yt-dlp binary: the url is decoded locally, while metadata and the transfer of the
 * file are handed to the tool. The staging of the target file stays with the core, as for a native
 * adapter.
 *
 * The tool runs in the home directory of the application: a config file of the current directory
 * never reaches it, and the process boundary is a seam of its own, so a test drives the adapter
 * without starting anything.
 */
class YoutubeSourceAdapter internal constructor(
    private val runner: YtDlpProcessRunner,
    private val config: () -> YtDlpConfig,
) : SourceAdapter {

    constructor(config: () -> YtDlpConfig) : this(YtDlpProcessRunner(workingDirectoryOf(config())), config)

    override val capability: DownloadCapability = DownloadCapability.Delegate

    override val displayName: String = "YouTube"

    override suspend fun find(input: String): FindResult {
        val url = runCatching { URI(withScheme(input.trim())) }.getOrNull()
        val host = url?.host ?: return FindResult.Unsupported
        return when {
            SHORT_HOST.matches(host) -> found(url.path?.trim('/'))
            YOUTUBE_HOST.matches(host) -> found(mediaIdOf(url))
            else -> FindResult.Unsupported
        }
    }

    override suspend fun loadMeta(id: String): LoadMetaResult {
        val settings = config()
        val run =
            runner.run(resolveCommand(settings, id), timeout = settings.resolveTimeout).getOrElse { failure ->
                return LoadMetaResult.Failed(downloadErrorOf(failure))
            }
        val meta = ytDlpPayload(run.output.joinToString("\n"))?.let { payload -> mediaMetaOf(payload, id) }
        return meta?.let(LoadMetaResult::Found) ?: LoadMetaResult.Failed(DownloadError.ExtractorBroken)
    }

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (Progress) -> Unit,
    ): DownloadResult = DownloadResult.Failed(DownloadError.ExtractorBroken) // stub, filled by #93

    private fun resolveCommand(settings: YtDlpConfig, id: String): List<String> =
        listOf(binaryOf(settings), RESOLVE, SKIP_DOWNLOAD, watchUrlOf(id))

    private fun mediaIdOf(url: URI): String? {
        val path = url.path.orEmpty()
        return if (WATCH_PATH.matches(path)) watchIdOf(url.query) else idInPath(path)
    }

    private fun idInPath(path: String): String? = ID_PATH.matchEntire(path)?.groupValues?.get(1)

    private fun watchIdOf(query: String?): String? =
        query
            .orEmpty()
            .split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == ID_PARAM }
            ?.get(1)

    private fun found(id: String?): FindResult =
        id?.takeIf { MEDIA_ID.matches(it) }?.let(FindResult::Found) ?: FindResult.NotFound

    private fun withScheme(input: String): String =
        if (input.contains(SCHEME_SEPARATOR)) input else DEFAULT_SCHEME + input.trimStart('/')
}

private fun workingDirectoryOf(settings: YtDlpConfig): Path = settings.ffmpegLocation.parent ?: settings.ffmpegLocation
