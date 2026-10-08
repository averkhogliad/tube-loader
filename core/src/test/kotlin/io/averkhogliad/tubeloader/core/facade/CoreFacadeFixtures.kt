package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.FakeSourceAdapter
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import io.averkhogliad.tubeloader.core.download.qualities
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.string
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val testHttpTool = HttpToolConfig(perAttemptTimeout = 30.seconds)
internal val mediaIds = Arb.string(1..12)
internal val inputs = Arb.string(1..24)

internal fun scratchDir(): Path = Files.createTempDirectory("tubeloader-test")

private val worldsByDir = mutableMapOf<Path, MutableList<FacadeWorld>>()

private val retryPauses = listOf(20, 40, 80, 160).map { it.milliseconds }

// cancelling is immediate, but a scope on a test dispatcher cannot finish its cancelled children
// once the test scheduler stops being advanced: the join has to be bounded, and the retry below
// covers the deletion of a file a still-running writer held
private val stopBudget = 1.seconds

internal suspend fun cleanUp(dir: Path) {
    closeWorldsIn(dir)
    deleteWithRetry(dir)
}

private suspend fun closeWorldsIn(dir: Path) {
    // a world may be built over another world's directory, so its own directory is the key and
    // every world nested under the spec's directory has to be stopped, not just the direct one
    val worlds =
        synchronized(worldsByDir) {
            val nested = worldsByDir.keys.filter { it.startsWith(dir) }
            nested.mapNotNull { worldsByDir.remove(it) }.flatten()
        }
    worlds.forEach { it.close() }
}

@OptIn(ExperimentalPathApi::class)
private suspend fun deleteWithRetry(dir: Path) {
    var lastFailure: Exception? = null
    repeat(retryPauses.size + 1) { attempt ->
        try {
            dir.deleteRecursively()
            return
        } catch (failure: Exception) {
            lastFailure = failure
            retryPauses.getOrNull(attempt)?.let { delay(it) }
        }
    }
    throw lastFailure!!
}

internal data class FacadeSettings(
    val adapters: List<FakeSourceAdapter> = listOf(FakeSourceAdapter()),
    val initialConfig: AppConfig = AppConfig(httpTool = testHttpTool),
    val taskIdGenerator: TaskIdGenerator = RandomTaskIdGenerator,
    val clock: Clock = Clock.System,
)

internal class FacadeWorld(
    rootDir: Path,
    val adapters: List<FakeSourceAdapter>,
    val config: MutableStateFlow<AppConfig>,
    val parentScope: CoroutineScope,
    val queue: DownloadQueue,
    val facade: CoreFacade,
) {
    val tempDir: Path = Files.createTempDirectory(rootDir, "case")

    private val witness = CoroutineScope(Dispatchers.Unconfined + Job())
    private val observed = mutableMapOf<TaskId, MutableList<DownloadStatus>>()
    private val latest = mutableMapOf<TaskId, DownloadState>()
    private var targets = 0

    suspend fun close() {
        stop(parentScope)
        stop(witness)
    }

    private suspend fun stop(scope: CoroutineScope) {
        val job = scope.coroutineContext[Job] ?: return
        job.cancel()
        withTimeoutOrNull(stopBudget) { job.join() }
    }

    fun enqueue(ref: MediaRef, target: Path): DownloadHandle {
        val handle = facade.enqueue(ref, Arb.qualities().next(), target)
        witness.launch {
            handle.state.collect { state ->
                latest[handle.taskId] = state
                observed.getOrPut(handle.taskId) { mutableListOf() } += state.status
            }
        }
        return handle
    }

    fun enqueue(ref: MediaRef): DownloadHandle = enqueue(ref, targetPath())

    fun targetPath(): Path = tempDir.resolve("video-${targets++}.mp4")

    fun resolve(mediaId: String): MediaRef = MediaRef(facade.availableSources.single(), mediaId)

    fun state(taskId: TaskId): DownloadState = latest.getValue(taskId)

    fun observedStatuses(taskId: TaskId): List<DownloadStatus> = observed[taskId].orEmpty().distinct()

    suspend fun awaitState(taskId: TaskId, predicate: (DownloadState) -> Boolean) {
        withTimeout(5.seconds) {
            while (!predicate(latest[taskId] ?: return@withTimeout)) {
                yield()
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun facadeWorld(
    tempDir: Path,
    settings: FacadeSettings = FacadeSettings(),
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    workContext: CoroutineContext = dispatcher,
): FacadeWorld {
    val scope = CoroutineScope(dispatcher)
    val config = MutableStateFlow(settings.initialConfig)
    val queue = DownloadQueue(scope, dispatcher, workContext, config)
    val facade = CoreFacade(settings.adapters, queue, settings.taskIdGenerator, settings.clock)
    val world = FacadeWorld(tempDir, settings.adapters, config, scope, queue, facade)
    synchronized(worldsByDir) { worldsByDir.getOrPut(world.tempDir) { mutableListOf() } += world }
    return world
}

internal fun leftoverFilesIn(dir: Path, expected: Path): List<Path> =
    Files.newDirectoryStream(dir).use { entries ->
        entries.filter {
            it !=
                expected
        }
    }

internal fun partialsIn(dir: Path): List<Path> =
    Files.newDirectoryStream(dir).use { entries ->
        entries.filter { it.fileName.toString().contains(".part-") }
    }
