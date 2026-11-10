package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.adapters.rutube.RutubeSourceAdapter
import io.averkhogliad.tubeloader.adapters.youtube.YoutubeSourceAdapter
import io.averkhogliad.tubeloader.config.Config
import io.averkhogliad.tubeloader.config.ConfigProvider
import io.averkhogliad.tubeloader.config.defaultSources
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.adapter.adaptersByCode
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.http.HttpTools
import io.averkhogliad.tubeloader.core.media.FfmpegMediaTool
import io.averkhogliad.tubeloader.core.port.HttpTool
import io.averkhogliad.tubeloader.core.port.MediaTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_TARGETS: List<SmokeTarget> = listOf()

private val SHUTDOWN_BUDGET = 30.seconds

internal const val RUTUBE_CODE = "rutube"
internal const val YOUTUBE_CODE = "youtube"

internal class SmokeComposition(
    val session: SmokeSession,
    val stderr: LineSinkStream,
    val client: HttpClient,
    val mediaTool: FfmpegMediaTool,
    val scope: CoroutineScope,
)

fun main(args: Array<String>) {
    val cli = parseCli(args)
    val targets = cli.targets.ifEmpty { DEFAULT_TARGETS }
    val targetDir = Paths.get(cli.targetDir)
    runScript(targets, targetDir, cli.parallel, cli.cancelAfter)
}

private fun runScript(targets: List<SmokeTarget>, targetDir: Path, parallel: Int, cancelAfter: Duration?) {
    val composition = buildComposition(targetDir, parallel)
    val session = composition.session
    try {
        checkSourceCodes(session, targets)
        val rows = targetRows(targets)
        rows.forEach { row -> session.dashboard.register(row.key, formatPlanned(row)) }
        runBlocking {
            // the tools of the run are fetched at startup: a target waits for its source, not for the network
            session.tools.ensureFfmpeg()
            session.tools.ensureYtDlp()
            val observerJob = launch { session.observer.observe() }
            val cancelJob = launchCancelJobs(session, cancelAfter)
            try {
                coroutineScope {
                    targets.forEachIndexed { index, target ->
                        launch { runOne(session, target, rows[index]) }
                    }
                }
            } finally {
                observerJob.cancelAndJoin()
                cancelJob.cancelAndJoin()
            }
        }
    } finally {
        composition.stderr.drain()
        session.dashboard.close()
        runCatching { runBlocking { session.shutdown(SHUTDOWN_BUDGET) } }
        runCatching { composition.client.close() }
        runCatching { composition.mediaTool.close() }
        composition.scope.cancel()
    }
}

/**
 * The composition root of the smoke run: the graph of the core is built by hand, source by source.
 *
 * The graph of the application itself is a stage of its own — this tool only proves that the pieces
 * fit together, so every dependency is named here instead of being injected.
 */
private fun buildComposition(targetDir: Path, parallel: Int): SmokeComposition {
    createDirectories(targetDir)
    val homeDir = targetDir.resolve(".tubeloader")
    createDirectories(homeDir)
    val config = loadConfig(homeDir)
    val containerFormat = readContainerFormat(config)
    val httpToolConfig = HttpToolConfig(perAttemptTimeout = 60.seconds)
    val dashboard = ProgressDashboard()
    val stderr = LineSinkStream(dashboard::note)
    System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
    val appConfig =
        MutableStateFlow(
            AppConfig(
                maxParallelDownloads = parallel,
                defaultTargetDir = targetDir,
                httpTool = httpToolConfig,
            ),
        )
    val client =
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = httpToolConfig.connectTimeout.inWholeMilliseconds
                requestTimeoutMillis = httpToolConfig.requestTimeout.inWholeMilliseconds
            }
            install(ContentEncoding) { gzip() }
            expectSuccess = false
            followRedirects = false
        }
    val httpTool = LoggingHttpTool(HttpTools.create(client), dashboard::note)
    val paths = ToolPaths.of(homeDir)
    val mediaTool =
        FfmpegMediaTool(
            MediaToolConfig(paths = paths, containerFormat = containerFormat),
            LocalProcessRunner(),
        )
    val ytDlpConfig = YtDlpConfig.fromConfig(config, paths)
    val adapters = smokeSources(httpTool, mediaTool, httpToolConfig, ytDlpConfig)
    val parentJob = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.IO + parentJob)
    val queue = DownloadQueue(scope, Dispatchers.IO, Dispatchers.IO, appConfig)
    val facade = CoreFacade(adapters, queue, RandomTaskIdGenerator, Clock.System)
    val useCases = UseCases(facade, queue::shutdown)
    val observer = DownloadStateObserver(useCases.observeDownloads, dashboard)
    val tools = SmokeTools(paths, dashboard)
    val session = SmokeSession(targetDir, dashboard, useCases, observer, tools)
    return SmokeComposition(session, stderr, client, mediaTool, scope)
}

/**
 * The sources the smoke routes to: every adapter the tool knows, built by hand, under the code that
 * names the source.
 *
 * The graph of the application itself is a stage of its own — this tool only proves that the pieces
 * fit together, so every dependency is named here instead of being injected.
 */
internal fun smokeSources(
    httpTool: HttpTool,
    mediaTool: MediaTool,
    httpToolConfig: HttpToolConfig,
    ytDlpConfig: YtDlpConfig,
): Map<String, SourceAdapter> =
    adaptersByCode(
        listOf(
            RUTUBE_CODE to RutubeSourceAdapter(httpTool, mediaTool) { httpToolConfig },
            YOUTUBE_CODE to YoutubeSourceAdapter { ytDlpConfig },
        ),
    )

/**
 * The codes of the targets must be known before the first download starts.
 *
 * The list of the sources is known only after the graph is built, so the check lives here and not in
 * the parsing of the command. The registry of the sources is not duplicated: the codes come from the
 * facade.
 */
internal fun requireKnownSourceCodes(targets: List<SmokeTarget>, knownCodes: List<String>) {
    val unknown =
        targets
            .filterIsInstance<SmokeTarget.ById>()
            .map { it.code }
            .distinct()
            .filter { it !in knownCodes }
    if (unknown.isEmpty()) return
    error(
        "unknown source code: ${unknown.joinToString(", ")}; " +
            "known codes: ${knownCodes.joinToString(", ")}\n${CliUsage.USAGE}",
    )
}

/**
 * The check of the codes of the targets, printed before the failure leaves the tool.
 *
 * The stderr of the tools is routed into the dashboard, so the stack trace of the failure would be
 * swallowed by the redirect: the answer of the command is printed instead.
 */
private fun checkSourceCodes(session: SmokeSession, targets: List<SmokeTarget>) {
    val resolveMedia = session.useCases.resolveMedia
    val codes = resolveMedia.sources.map { it.code }
    try {
        requireKnownSourceCodes(targets, codes)
    } catch (failure: IllegalStateException) {
        session.dashboard.note(failure.message ?: "the command names an unknown source")
        throw failure
    }
}

private fun loadConfig(homeDir: Path): Config {
    val provider = ConfigProvider()
    defaultSources(homeDir = homeDir).forEach { source -> provider.addSource(source) }
    return provider.load()
}

private fun readContainerFormat(config: Config): String =
    config
        .getOrNull("download.container-format")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: MediaToolConfig.DEFAULT_CONTAINER_FORMAT
