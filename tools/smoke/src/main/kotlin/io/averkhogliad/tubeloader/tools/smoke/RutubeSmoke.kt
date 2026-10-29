package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.adapters.rutube.RutubeSourceAdapter
import io.averkhogliad.tubeloader.config.Config
import io.averkhogliad.tubeloader.config.ConfigProvider
import io.averkhogliad.tubeloader.config.defaultSources
import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.http.HttpTools
import io.averkhogliad.tubeloader.core.media.FfmpegMediaTool
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_PAIRS: List<Pair<String, String>> = listOf()

private val QUALITY_1080P = Quality("1080p", TrackKind.Video, "1080p")
private val SHUTDOWN_BUDGET = 30.seconds

internal data class DownloadPlan(val ref: MediaRef, val meta: MediaMeta)

internal class SmokeComposition(
    val session: SmokeSession,
    val stderr: LineSinkStream,
    val client: HttpClient,
    val mediaTool: FfmpegMediaTool,
    val scope: CoroutineScope,
)

fun main(args: Array<String>) {
    val cli = parseCli(args)
    val pairs = cli.pairs.ifEmpty { DEFAULT_PAIRS }
    val targetDir = Paths.get(cli.targetDir)
    runScript(pairs, targetDir, cli.parallel, cli.cancelAfter, cli.cancelTaskId)
}

private fun runScript(
    pairs: List<Pair<String, String>>,
    targetDir: Path,
    parallel: Int,
    cancelAfter: Duration?,
    cancelTaskId: TaskId?,
) {
    val composition = buildComposition(targetDir, parallel, cancelTaskId)
    val session = composition.session
    val workDone = AtomicBoolean(false)
    val shutdownHook = registerCancelOnShutdown(session)
    try {
        runBlocking {
            val observerJob = launch { session.observer.observe() }
            val cancelJob = launchCancelJobs(session, cancelAfter, cancelTaskId, workDone)
            try {
                coroutineScope {
                    pairs.forEach { (id, name) ->
                        launch { runOne(session, id, name) }
                    }
                }
            } finally {
                workDone.set(true)
                observerJob.cancelAndJoin()
                cancelJob.cancelAndJoin()
            }
        }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        composition.stderr.drain()
        session.dashboard.close()
        runCatching { runBlocking { session.useCases.shutdown.shutdown(SHUTDOWN_BUDGET) } }
        runCatching { composition.client.close() }
        runCatching { composition.mediaTool.close() }
        composition.scope.cancel()
    }
}

private fun buildComposition(targetDir: Path, parallel: Int, cancelTaskId: TaskId?): SmokeComposition {
    createDirectories(targetDir)
    val homeDir = targetDir.resolve(".tubeloader")
    createDirectories(homeDir)
    val containerFormat = readContainerFormat(loadConfig(homeDir))
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
    val adapter = RutubeSourceAdapter(httpTool, mediaTool) { httpToolConfig }
    val parentJob = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.IO + parentJob)
    val queue = DownloadQueue(scope, Dispatchers.IO, Dispatchers.IO, appConfig)
    val facade = CoreFacade(listOf(adapter), queue, taskIdGenerator(cancelTaskId), Clock.System)
    val useCases = UseCases(facade, queue)
    val observer = DownloadStateObserver(useCases.observeDownloads, dashboard)
    val session = SmokeSession(targetDir, dashboard, useCases, observer)
    return SmokeComposition(session, stderr, client, mediaTool, scope)
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

private suspend fun runOne(session: SmokeSession, id: String, name: String) {
    session.dashboard.register(id, "$name ($id): resolving")
    val plan = planDownload(session, id, name) ?: return
    val safeName = sanitize(name)
    val target = session.targetDir.resolve("$safeName.mp4")
    val handle = session.useCases.enqueueDownload.enqueue(plan.ref, pickQuality(plan.meta), target)
    session.observer.register(handle.taskId, DownloadEntry(name, safeName))
    session.dashboard.complete(id, "[${handle.taskId}] $name: enqueued, ${plan.meta.title}")
    session.observer.awaitTerminal(handle.taskId)
}

private suspend fun planDownload(session: SmokeSession, id: String, name: String): DownloadPlan? =
    when (val resolved = session.useCases.resolveUrl.byUrl("https://rutube.ru/video/$id/")) {
        is ResolveResult.Resolved -> {
            loadPlan(session, id, name, resolved.ref)
        }

        ResolveResult.NotFound -> {
            session.dashboard.complete(id, "$name ($id): skipped, source says NotFound")
            null
        }

        ResolveResult.Unsupported -> {
            session.dashboard.complete(id, "$name ($id): skipped, URL not supported by any adapter")
            null
        }
    }

private suspend fun loadPlan(session: SmokeSession, id: String, name: String, ref: MediaRef): DownloadPlan? =
    when (val loaded = session.useCases.loadMedia.load(ref)) {
        is LoadMetaResult.Found -> {
            DownloadPlan(ref, loaded.meta)
        }

        LoadMetaResult.NotFound -> {
            session.dashboard.complete(id, "$name ($id): skipped, metadata not found")
            null
        }

        is LoadMetaResult.Failed -> {
            session.dashboard.complete(id, "$name ($id): skipped, metadata failed: ${loaded.error}")
            null
        }
    }

private fun pickQuality(meta: MediaMeta): Quality =
    meta.qualities.firstOrNull { it.kind == TrackKind.Video } ?: QUALITY_1080P

private fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
