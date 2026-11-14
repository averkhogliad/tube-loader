package io.averkhogliad.tubeloader.app

import io.averkhogliad.tubeloader.adapters.rutube.RutubeSourceAdapter
import io.averkhogliad.tubeloader.adapters.youtube.YoutubeSourceAdapter
import io.averkhogliad.tubeloader.adapters.youtube.YtDlpProcessRunner
import io.averkhogliad.tubeloader.adapters.youtube.ytDlpPolicy
import io.averkhogliad.tubeloader.config.ConfigReader
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.adapter.adaptersByCode
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.Source
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.port.HttpTool
import io.averkhogliad.tubeloader.core.port.MediaTool
import io.averkhogliad.tubeloader.infra.binaries.ManagedBinary
import io.averkhogliad.tubeloader.infra.binaries.SystemProcessRunner
import io.averkhogliad.tubeloader.infra.http.HttpTools
import io.averkhogliad.tubeloader.infra.media.FfmpegMediaTool
import io.averkhogliad.tubeloader.infra.media.ffmpegPolicy
import io.averkhogliad.tubeloader.usecase.DownloadSession
import io.averkhogliad.tubeloader.usecase.ResolveMedia
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Duration

private const val RUTUBE_CODE = "rutube"
private const val YOUTUBE_CODE = "youtube"
private const val CONTAINER_FORMAT_KEY = "download.container-format"

/**
 * The composition root of the application: the single place the graph of the core is wired.
 *
 * The graph is assembled by [build] from a source-agnostic [ConfigReader], the paths of the external
 * tools and the [AppOverrides] of the frontend. A frontend names the sources, the use cases and the
 * lifecycle; the ports, the adapters and the infra behind them stay inside this module.
 */
class TubeloaderApp internal constructor(
    val config: AppConfig,
    private val facade: CoreFacade,
    private val queue: DownloadQueue,
    private val tools: ToolsPreparation,
    private val resources: AppResources,
) : AutoCloseable {

    /** The sources the application routes to, under the codes they are known by. */
    val sources: List<Source> get() = facade.availableSources

    /** The resolution of a media item: by url, or by the code of a source and a media id. */
    val resolveMedia: ResolveMedia = ResolveMedia(facade)

    /** The downloads of this run: queueing, cancellation and the aggregated states. */
    val downloads: DownloadSession = DownloadSession(facade)

    /**
     * Fetches the external tools of the application once and answers the status of each of them.
     *
     * A repeated call answers the status the first one prepared; every tool that cannot be
     * obtained is named in [onNote] and does not stop the other one.
     */
    suspend fun prepareTools(onNote: (String) -> Unit): PreparedTools = tools.prepare(onNote)

    /** Stops the download queue and waits for its work within [timeout]; the result says whether it fit. */
    suspend fun shutdown(timeout: Duration): Boolean = queue.shutdown(timeout)

    /** Releases the client, the media tool and the scope of the graph. */
    override fun close() {
        resources.close()
    }

    companion object {

        /**
         * Builds the whole graph: the http client, the managed binaries of the tools, the media
         * tool, the adapters of the sources, the download queue, the facade and the use cases.
         *
         * [reader] isolates the source of the settings: a TOML file, code or memory. [paths] names
         * the home directory of the application and the binaries of the tools. [overrides] are the
         * values of the frontend over the config; a value the config does not name keeps its
         * default.
         */
        fun build(reader: ConfigReader, paths: ToolPaths, overrides: AppOverrides = AppOverrides()): TubeloaderApp {
            val config = overridden(AppConfig.fromReader(reader), overrides)
            val ytDlpConfig = YtDlpConfig.fromReader(reader, paths)
            val client = httpClient(config.httpTool)
            val httpTool = HttpTools.create(client)
            val ffmpegBinary = ManagedBinary(paths.ffmpegBinary, { ffmpegPolicy() })
            val ytDlpBinary = ManagedBinary(paths.ytDlpBinary, { ytDlpPolicy() })
            val mediaToolConfig = MediaToolConfig(paths = paths, containerFormat = containerFormat(reader))
            val mediaTool = FfmpegMediaTool(mediaToolConfig, SystemProcessRunner(), ffmpegBinary)
            val adapters = adaptersOf(httpTool, mediaTool, config.httpTool, ytDlpConfig, ytDlpBinary)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val queue = queueOf(scope, config)
            val facade = CoreFacade(adapters, queue, RandomTaskIdGenerator, Clock.System)
            val tools =
                ToolsPreparation(
                    prepareFfmpeg = { ffmpegBinary.ensureBinary(mediaToolConfig.binaryPath) },
                    prepareYtDlp = { ytDlpBinary.ensureBinary(ytDlpConfig.binaryPath) },
                )
            return TubeloaderApp(config, facade, queue, tools, AppResources(client, mediaTool, scope))
        }

        private fun overridden(config: AppConfig, overrides: AppOverrides): AppConfig =
            config.copy(
                maxParallelDownloads = overrides.maxParallelDownloads ?: config.maxParallelDownloads,
                defaultTargetDir = overrides.defaultTargetDir ?: config.defaultTargetDir,
            )

        private fun httpClient(config: HttpToolConfig): HttpClient =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
                    requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
                }
                install(ContentEncoding) { gzip() }
                expectSuccess = false
                followRedirects = false
            }

        private fun adaptersOf(
            httpTool: HttpTool,
            mediaTool: MediaTool,
            httpConfig: HttpToolConfig,
            ytDlpConfig: YtDlpConfig,
            ytDlpBinary: ManagedBinary,
        ): Map<String, SourceAdapter> =
            adaptersByCode(
                listOf(
                    RUTUBE_CODE to RutubeSourceAdapter(httpTool, mediaTool) { httpConfig },
                    YOUTUBE_CODE to
                        YoutubeSourceAdapter(
                            YtDlpProcessRunner(workingDirectory = ytDlpConfig.paths.homeDir),
                            { ytDlpConfig },
                            ytDlpBinary,
                        ),
                ),
            )

        private fun queueOf(scope: CoroutineScope, config: AppConfig): DownloadQueue =
            DownloadQueue(scope, Dispatchers.IO, Dispatchers.IO, MutableStateFlow(config))
    }
}

/**
 * The container of the output of the media tool: the key of the download block, or the default of
 * the tool when the config names none. An unreadable value keeps the lenient reading of the block:
 * a broken format does not stop the run.
 */
internal fun containerFormat(reader: ConfigReader): String =
    reader
        .text(CONTAINER_FORMAT_KEY)
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() }
        ?: MediaToolConfig.DEFAULT_CONTAINER_FORMAT

/**
 * What the application has to release on the way out: the client of the http port, the media tool
 * and the scope of the graph.
 */
internal class AppResources(
    private val client: HttpClient,
    private val mediaTool: MediaTool,
    private val scope: CoroutineScope,
) : AutoCloseable {

    override fun close() {
        client.close()
        mediaTool.close()
        scope.cancel()
    }
}
