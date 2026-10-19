package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.adapters.rutube.RutubeSourceAdapter
import io.averkhogliad.tubeloader.config.Config
import io.averkhogliad.tubeloader.config.ConfigProvider
import io.averkhogliad.tubeloader.config.defaultSources
import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.http.HttpTools
import io.averkhogliad.tubeloader.core.media.FfmpegMediaTool
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.PrintStream
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Suppress("MaxLineLength")
private val DEFAULT_PAIRS: List<Pair<String, String>> = listOf()

private val QUALITY_1080P = Quality("1080p", TrackKind.Video, "1080p")
private val SHUTDOWN_BUDGET = 30.seconds

fun main(args: Array<String>) {
    val cli = parseCli(args)
    val pairs = cli.pairs.ifEmpty { DEFAULT_PAIRS }
    val targetDir = Paths.get(cli.targetDir)
    runScript(pairs, targetDir, cli.parallel)
}

private fun runScript(pairs: List<Pair<String, String>>, targetDir: Path, parallel: Int) {
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
    val mediaTool =
        FfmpegMediaTool(
            MediaToolConfig(homeDir = homeDir, containerFormat = containerFormat),
            LocalProcessRunner(),
        )
    val adapter = RutubeSourceAdapter(httpTool, mediaTool) { httpToolConfig }

    val parentJob = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.IO + parentJob)
    val queue = DownloadQueue(scope, Dispatchers.IO, Dispatchers.IO, appConfig)
    val facade = CoreFacade(listOf(adapter), queue, RandomTaskIdGenerator, Clock.System)

    try {
        runBlocking {
            coroutineScope {
                pairs.forEach { (id, name) ->
                    launch { runOne(facade, dashboard, id, name, targetDir) }
                }
            }
        }
    } finally {
        stderr.drain()
        dashboard.close()
        runCatching { runBlocking { queue.shutdown(SHUTDOWN_BUDGET) } }
        runCatching { client.close() }
        runCatching { mediaTool.close() }
        scope.cancel()
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

private suspend fun runOne(
    facade: CoreFacade,
    dashboard: ProgressDashboard,
    id: String,
    name: String,
    targetDir: Path,
) {
    dashboard.register(id, "$name ($id): resolving")
    val ref =
        when (val resolved = facade.findByUrl("https://rutube.ru/video/$id/")) {
            is ResolveResult.Resolved -> {
                resolved.ref
            }

            ResolveResult.NotFound -> {
                dashboard.complete(id, "$name ($id): skipped, source says NotFound")
                return
            }

            ResolveResult.Unsupported -> {
                dashboard.complete(id, "$name ($id): skipped, URL not supported by any adapter")
                return
            }
        }
    val safeName = sanitize(name)
    val target = targetDir.resolve("$safeName.mp4")
    val handle = facade.enqueue(ref, QUALITY_1080P, target)
    val terminal = awaitTerminal(handle, dashboard, id, name)
    dashboard.complete(id, formatTerminal(terminal, name, safeName))
}

private suspend fun awaitTerminal(
    handle: DownloadHandle,
    dashboard: ProgressDashboard,
    key: String,
    name: String,
): DownloadState {
    var last: DownloadState? = null
    withContext(Dispatchers.IO) {
        handle.state
            .onEach { state ->
                last = state
                dashboard.update(key, formatProgress(state, name))
            }.map { it.status }
            .distinctUntilChanged()
            .first { it is DownloadStatus.Completed || it is DownloadStatus.Cancelled || it is DownloadStatus.Failed }
    }
    return last ?: error("terminal state for $name was not captured")
}

private fun sanitize(name: String): String =
    name
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "video" }
