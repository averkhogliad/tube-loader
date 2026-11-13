package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.retry.RetryPolicy
import io.averkhogliad.tubeloader.retry.continueIf
import io.averkhogliad.tubeloader.retry.exponentialBackoff
import io.averkhogliad.tubeloader.retry.retry
import io.averkhogliad.tubeloader.retry.stopAtAttempts
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
 *
 * The binary itself is not shipped with the application: the first call fetches the official build
 * into the home directory and every call starts the file by its absolute path, so the machine does
 * not have to carry the tool.
 */
class YoutubeSourceAdapter internal constructor(
    private val runner: YtDlpProcessRunner,
    private val config: () -> YtDlpConfig,
) : SourceAdapter {

    private val distribution = YtDlpDistribution(config().paths)

    constructor(config: () -> YtDlpConfig) : this(YtDlpProcessRunner(workingDirectoryOf(config())), config)

    override val capability: DownloadCapability = DownloadCapability.Delegate

    override val displayName: String = "YouTube"

    override suspend fun resolveId(input: String): ResolveIdResult {
        val url = runCatching { URI(withScheme(input.trim())) }.getOrNull()
        val host = url?.host ?: return ResolveIdResult.Unsupported
        return when {
            SHORT_HOST.matches(host) -> found(url.path?.trim('/'))
            YOUTUBE_HOST.matches(host) -> found(mediaIdOf(url))
            else -> ResolveIdResult.Unsupported
        }
    }

    override suspend fun findById(id: String): LoadMetaResult {
        val settings = config()
        return distribution
            .ensureBinary(settings.binaryPath)
            .fold(
                onSuccess = { binary -> resolveMeta(settings, binary, id) },
                onFailure = { LoadMetaResult.Failed(DownloadError.ExtractorBroken) },
            )
    }

    private suspend fun resolveMeta(settings: YtDlpConfig, binary: Path, id: String): LoadMetaResult {
        val result =
            retry(policyOf(settings)) {
                runner.run(resolveCommand(binary, id), timeout = settings.resolveTimeout)
            }
        return result.fold(
            onSuccess = { run ->
                val meta =
                    ytDlpPayload(run.output.joinToString("\n"))?.let { payload -> mediaMetaOf(payload, id) }
                meta?.let(LoadMetaResult::Found)
                    ?: LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            },
            onFailure = { failure -> LoadMetaResult.Failed(downloadErrorOf(failure)) },
        )
    }

    override suspend fun download(
        mediaId: String,
        quality: Quality,
        targetPath: Path,
        onProgress: (Progress) -> Unit,
    ): DownloadResult {
        val settings = config()
        val binary = distribution.ensureBinary(settings.binaryPath).getOrNull()
        val selector = formatSelectorOf(quality)
        return if (binary == null || selector == null) {
            DownloadResult.Failed(DownloadError.ExtractorBroken)
        } else {
            val staging = stagingDirectoryOf(settings, mediaId)
            try {
                val command = downloadCommand(settings, binary, mediaId, selector, staging)
                transfer(runner, command, settings, targetPath, onProgress)
            } finally {
                runCatching { staging.removeTree() }
            }
        }
    }

    private fun resolveCommand(binary: Path, id: String): List<String> =
        listOf(binary.toString(), RESOLVE, SKIP_DOWNLOAD, watchUrlOf(id))

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

    private fun found(id: String?): ResolveIdResult =
        id?.takeIf { MEDIA_ID.matches(it) }?.let(ResolveIdResult::Found) ?: ResolveIdResult.NotFound

    private fun withScheme(input: String): String =
        if (input.contains(SCHEME_SEPARATOR)) input else DEFAULT_SCHEME + input.trimStart('/')
}

private fun workingDirectoryOf(settings: YtDlpConfig): Path = settings.paths.homeDir

private val RETRIABLE = setOf(DownloadError.NetworkTransient, DownloadError.UrlExpired)

internal fun policyOf(settings: YtDlpConfig): RetryPolicy =
    RetryPolicy
        .stopAtAttempts(settings.retryMaxAttempts)
        .continueIf { downloadErrorOf(failure) in RETRIABLE }
        .exponentialBackoff(settings.retryBaseDelay, randomizationFactor = settings.retryRandomizationFactor)
