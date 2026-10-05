package io.averkhogliad.tubeloader.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val mediaIds = Arb.string(1..12)
private val inputs = Arb.string(1..24)

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeTest :
    FreeSpec({

        val tempDir = Files.createTempDirectory("tubeloader-test")

        afterSpec {
            @OptIn(ExperimentalPathApi::class)
            tempDir.deleteRecursively()
        }

        "findByUrl" - {
            "returns Resolved with the id and source when one adapter claims the input" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Resolved(
                        MediaRef(world.facade.availableSources.single(), mediaId),
                    )
                }
            }

            "returns Unsupported when no adapter claims the input" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Unsupported }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Unsupported
                }
            }

            "returns NotFound when the adapter recognizes the source but not the media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.NotFound }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.NotFound
                }
            }

            "routes to the matching adapter among several" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    val mediaId = mediaIds.next()
                    second.onFind = { FindResult.Found(mediaId) }
                    val world = facadeWorld(tempDir, adapters = listOf(first, second))

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Resolved(
                        MediaRef(world.facade.availableSources[1], mediaId),
                    )
                }
            }

            "throws on ambiguous match by two adapters" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Found("id-1") }
                    second.onFind = { FindResult.Found("id-2") }
                    val world = facadeWorld(tempDir, adapters = listOf(first, second))

                    // when + then
                    shouldThrow<IllegalStateException> {
                        world.facade.findByUrl(inputs.next())
                    }
                }
            }

            "prefers NotFound over Unsupported when both occur" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    second.onFind = { FindResult.NotFound }
                    val world = facadeWorld(tempDir, adapters = listOf(first, second))

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.NotFound
                }
            }
        }

        "findById" - {
            "returns Resolved when the named adapter recognizes the id" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    val mediaId = mediaIds.next()
                    second.onFind = { FindResult.Found(mediaId) }
                    val world = facadeWorld(tempDir, adapters = listOf(first, second))
                    val secondSource = world.facade.availableSources[1]

                    // when
                    val actual = world.facade.findById(secondSource.id, mediaId)

                    // then
                    actual shouldBe ResolveResult.Resolved(MediaRef(secondSource, mediaId))
                }
            }

            "throws on unknown source id" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)

                    // when + then
                    shouldThrow<IllegalStateException> {
                        world.facade.findById(SourceId(99), mediaIds.next())
                    }
                }
            }
        }

        "loadMeta" - {
            "returns metadata from the resolved adapter" {
                runTest {
                    // given
                    val meta = Arb.mediaMetas().next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(meta.id) }
                    world.adapters.single().onLoadMeta = { LoadMetaResult.Found(meta) }
                    val ref = world.resolve(meta.id)

                    // when
                    val actual = world.facade.loadMeta(ref)

                    // then
                    actual shouldBe LoadMetaResult.Found(meta)
                }
            }

            "returns NotFound when the adapter has no such media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaIds.next()) }
                    world.adapters.single().onLoadMeta = { LoadMetaResult.NotFound }
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val actual = world.facade.loadMeta(ref)

                    // then
                    actual shouldBe LoadMetaResult.NotFound
                }
            }
        }

        "enqueue" - {
            "walks the download from Queued through Downloading to Completed" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        DownloadResult.Success
                    }

                    // when
                    val handle = world.enqueue(ref)
                    testScheduler.advanceUntilIdle()

                    // then
                    world.observedStatuses(handle.taskId) shouldBe listOf(
                        DownloadStatus.Queued,
                        DownloadStatus.LoadingMeta,
                        DownloadStatus.Downloading,
                    )
                    world.state(handle.taskId).status shouldBe DownloadStatus.Downloading
                    release.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    world.state(handle.taskId).status shouldBe DownloadStatus.Completed
                }
            }

            "keeps answering the commands while a download holds the io open" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, initialConfig = AppConfig(maxParallelDownloads = 1))
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        DownloadResult.Success
                    }

                    // when
                    val running = withTimeout(1.seconds) { world.enqueue(ref) }

                    // then
                    // the first download is parked inside the adapter and the facade must answer anyway
                    world.state(running.taskId).status shouldBe DownloadStatus.Downloading
                    val queued = withTimeout(1.seconds) { world.enqueue(ref) }
                    world.state(queued.taskId).status shouldBe DownloadStatus.Queued
                    withTimeout(1.seconds) { world.facade.findByUrl(inputs.next()) }
                        .shouldBeInstanceOf<ResolveResult.Resolved>()
                    withTimeout(1.seconds) { world.config.value = AppConfig(maxParallelDownloads = 2) }
                    world.config.value.maxParallelDownloads shouldBe 2
                    world.state(running.taskId).status shouldBe DownloadStatus.Downloading
                    release.complete(Unit)
                    world.awaitState(running.taskId) { it.status == DownloadStatus.Completed }
                    world.state(running.taskId).status shouldBe DownloadStatus.Completed
                }
            }

            "hands the core-issued partial path to the adapter" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val target = world.targetPath()

                    // when
                    val handle = world.enqueue(ref, target)

                    // then
                    val passed = world.adapters.single().downloaded.single()
                    passed.id shouldBe mediaId
                    passed.targetPath shouldNotBe target
                    passed.targetPath.parent shouldBe target.parent
                    passed.targetPath.fileName.toString() shouldContain handle.taskId.toString()
                }
            }

            "accepts media resolved by findById without a url" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val source = world.facade.availableSources.single()
                    val ref = (world.facade.findById(source.id, mediaId) as ResolveResult.Resolved).ref

                    // when
                    val handle = world.enqueue(ref)

                    // then
                    world.state(handle.taskId).status shouldBe DownloadStatus.Completed
                }
            }

            "error classes" - {
                listOf(
                    DownloadError.NotFound,
                    DownloadError.NetworkTransient,
                    DownloadError.UrlExpired,
                    DownloadError.ExtractorBroken,
                ).forEach { error ->
                    "reports $error when the adapter reports it" {
                        runTest {
                            // given
                            val mediaId = mediaIds.next()
                            val world = facadeWorld(tempDir)
                            world.adapters.single().onFind = { FindResult.Found(mediaId) }
                            world.adapters.single().onDownload = { _, _ -> DownloadResult.Failed(error) }
                            val ref = world.resolve(mediaId)

                            // when
                            val handle = world.enqueue(ref)

                            // then
                            val failed = world.state(handle.taskId).status as DownloadStatus.Failed
                            failed.error shouldBe error
                        }
                    }
                }
            }

            "fails the task with ExtractorBroken when the adapter throws" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val target = world.targetPath()
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        error("adapter broke")
                    }

                    // when
                    val handle = world.enqueue(ref, target)
                    testScheduler.advanceUntilIdle()

                    // then
                    world.observedStatuses(handle.taskId) shouldBe listOf(
                        DownloadStatus.Queued,
                        DownloadStatus.LoadingMeta,
                        DownloadStatus.Downloading,
                    )
                    release.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    val failed = world.state(handle.taskId).status as DownloadStatus.Failed
                    failed.error shouldBe DownloadError.ExtractorBroken
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "media tool failures" - {
                "fails the task and deletes the partial when mux fails" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val tool = FakeMediaTool()
                        tool.onMux = { _, _, _ -> Result.failure(IllegalStateException("mux failed")) }
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val target = world.targetPath()
                        val audio = world.targetPath()
                        world.adapters.single().onDownload = { download, _ ->
                            tool.mux(download.targetPath, audio, download.targetPath) {}.getOrThrow()
                            DownloadResult.Success
                        }

                        // when
                        val handle = world.enqueue(ref, target)

                        // then
                        val failed = world.state(handle.taskId).status as DownloadStatus.Failed
                        failed.error shouldBe DownloadError.ExtractorBroken
                        leftoverFilesIn(target.parent, target) shouldBe emptyList()
                    }
                }

                "passes both tracks to mux in video-then-audio order" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val tool = FakeMediaTool()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val target = world.targetPath()
                        val audio = world.targetPath()
                        var pathGivenToAdapter: Path? = null
                        world.adapters.single().onDownload = { download, _ ->
                            pathGivenToAdapter = download.targetPath
                            tool.mux(download.targetPath, audio, download.targetPath) {}
                            DownloadResult.Success
                        }

                        // when
                        world.enqueue(ref, target)

                        // then
                        val call = tool.muxCalls.single()
                        call.videoTrack shouldBe pathGivenToAdapter
                        call.audioTrack shouldBe audio
                        call.output shouldBe pathGivenToAdapter
                    }
                }
            }

            "task id allocation" - {
                "keeps both tasks when the generator repeats an occupied id" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val generated = listOf(TaskId(1), TaskId(1), TaskId(2))
                        var index = 0
                        val world = facadeWorld(
                            tempDir,
                            taskIdGenerator = TaskIdGenerator { generated[index++] },
                        )
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)

                        // when
                        val first = world.enqueue(ref)
                        val second = world.enqueue(ref)

                        // then
                        first.taskId shouldBe TaskId(1)
                        second.taskId shouldBe TaskId(2)
                        world.state(first.taskId).status shouldBe DownloadStatus.Completed
                        world.state(second.taskId).status shouldBe DownloadStatus.Completed
                    }
                }

                "fails when the generator keeps returning an occupied id" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir, taskIdGenerator = TaskIdGenerator { TaskId(1) })
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        world.enqueue(ref)

                        // when
                        val failure = shouldThrow<IllegalStateException> { world.enqueue(ref) }

                        // then
                        failure.message shouldContain "occupied id"
                    }
                }
            }

            "timestamps" - {
                "stamps the reservation and the terminal status from the injected clock" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val startedAt = Instant.parse("2026-09-30T10:00:00Z")
                        val finishedAt = Instant.parse("2026-09-30T10:05:00Z")
                        val moments = ArrayDeque(listOf(startedAt, finishedAt))
                        val clock = object : Clock {
                            override fun now(): Instant = moments.removeFirst()
                        }
                        val world = facadeWorld(tempDir, clock = clock)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)

                        // when
                        val handle = world.enqueue(ref)

                        // then
                        val state = world.state(handle.taskId)
                        state.taskId shouldBe handle.taskId
                        state.startedAt shouldBe startedAt
                        state.finishedAt shouldBe finishedAt
                    }
                }

                "leaves finishedAt null while the task is running" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val release = CompletableDeferred<Unit>()
                        world.adapters.single().onDownload = { _, _ ->
                            release.await()
                            DownloadResult.Success
                        }

                        // when
                        val handle = world.enqueue(ref)
                        val running = world.state(handle.taskId)
                        release.complete(Unit)

                        // then
                        running.finishedAt shouldBe null
                        world.awaitState(handle.taskId) { it.status == DownloadStatus.Completed }
                        world.state(handle.taskId).finishedAt shouldNotBe null
                    }
                }
            }

            "staging" - {
                "produces the final file atomically and leaves no partial behind" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val target = world.targetPath()

                        // when
                        val handle = world.enqueue(ref, target)

                        // then
                        world.state(handle.taskId).status shouldBe DownloadStatus.Completed
                        Files.exists(target) shouldBe true
                        leftoverFilesIn(target.parent, target) shouldBe emptyList()
                    }
                }

                "deletes the partial file when the adapter returns DownloadResult.Failed" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        world.adapters.single().onDownload = { _, _ ->
                            DownloadResult.Failed(DownloadError.NetworkTransient)
                        }
                        val ref = world.resolve(mediaId)
                        val target = world.targetPath()

                        // when
                        val handle = world.enqueue(ref, target)

                        // then
                        world.state(handle.taskId).status.shouldBeInstanceOf<DownloadStatus.Failed>()
                        Files.exists(target) shouldBe false
                        leftoverFilesIn(target.parent, target) shouldBe emptyList()
                    }
                }

                "replaces the target file that appears while the download is running" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val target = world.targetPath()
                        Files.writeString(target, "stale")
                        world.adapters.single().onDownload = { download, _ ->
                            Files.writeString(download.targetPath, "fresh")
                            DownloadResult.Success
                        }

                        // when
                        val handle = world.enqueue(ref, target)

                        // then
                        world.state(handle.taskId).status shouldBe DownloadStatus.Completed
                        Files.readString(target) shouldBe "fresh"
                        leftoverFilesIn(target.parent, target) shouldBe emptyList()
                    }
                }
            }

            "progress" - {
                "reports Determinate for absolute source progress" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val absolute = Arb.absoluteProgresses().next()
                        val reported = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        world.adapters.single().onDownload = { _, onProgress ->
                            onProgress(absolute)
                            reported.complete(Unit)
                            release.await()
                            DownloadResult.Success
                        }

                        // when
                        val handle = world.enqueue(ref)
                        reported.await()

                        // then
                        val state = world.state(handle.taskId)
                        state.status shouldBe DownloadStatus.Downloading
                        state.progress shouldBe Progress.Determinate(absolute.processed, absolute.total)
                        release.complete(Unit)
                    }
                }

                "reports Determinate for fractional source progress" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val fraction = Arb.fractions().next()
                        val reported = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        world.adapters.single().onDownload = { _, onProgress ->
                            onProgress(fraction)
                            reported.complete(Unit)
                            release.await()
                            DownloadResult.Success
                        }

                        // when
                        val handle = world.enqueue(ref)
                        reported.await()

                        // then
                        val state = world.state(handle.taskId)
                        state.status shouldBe DownloadStatus.Downloading
                        val determinate = state.progress as Progress.Determinate
                        (determinate.current.toDouble() / determinate.total) shouldBe
                            (fraction.ratio plusOrMinus 0.001)
                        release.complete(Unit)
                    }
                }

                listOf(
                    SourceProgress.Indeterminate,
                    SourceProgress.Absolute(1L, total = 0L),
                    SourceProgress.Fraction(Double.POSITIVE_INFINITY),
                    SourceProgress.Fraction(5.0),
                ).forEach { source ->
                    "falls back to Indeterminate for $source" {
                        runTest {
                            // given
                            val mediaId = mediaIds.next()
                            val world = facadeWorld(tempDir)
                            world.adapters.single().onFind = { FindResult.Found(mediaId) }
                            val ref = world.resolve(mediaId)
                            val reported = CompletableDeferred<Unit>()
                            val release = CompletableDeferred<Unit>()
                            world.adapters.single().onDownload = { _, onProgress ->
                                onProgress(source)
                                reported.complete(Unit)
                                release.await()
                                DownloadResult.Success
                            }

                            // when
                            val handle = world.enqueue(ref)
                            reported.await()

                            // then
                            world.state(handle.taskId).progress shouldBe Progress.Indeterminate
                            release.complete(Unit)
                        }
                    }
                }

                "updates the progress without changing the status while the task is active" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val absolute = Arb.absoluteProgresses().next()
                        lateinit var lateProgress: (SourceProgress) -> Unit
                        val reported = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        world.adapters.single().onDownload = { _, onProgress ->
                            lateProgress = onProgress
                            onProgress(absolute)
                            reported.complete(Unit)
                            release.await()
                            DownloadResult.Success
                        }
                        val handle = world.enqueue(ref)
                        reported.await()

                        // when
                        lateProgress(SourceProgress.Fraction(0.5))

                        // then
                        val state = world.state(handle.taskId)
                        state.status shouldBe DownloadStatus.Downloading
                        state.progress shouldBe Progress.Determinate(500L, 1000L)
                        release.complete(Unit)
                    }
                }

                "keeps the terminal progress when a callback arrives after the task completed" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val absolute = Arb.absoluteProgresses().next()
                        lateinit var lateProgress: (SourceProgress) -> Unit
                        val reported = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        world.adapters.single().onDownload = { _, onProgress ->
                            lateProgress = onProgress
                            onProgress(absolute)
                            reported.complete(Unit)
                            release.await()
                            DownloadResult.Success
                        }
                        val handle = world.enqueue(ref)
                        reported.await()
                        release.complete(Unit)
                        world.awaitState(handle.taskId) { it.status == DownloadStatus.Completed }

                        // when
                        lateProgress(SourceProgress.Fraction(0.5))

                        // then
                        val state = world.state(handle.taskId)
                        state.status shouldBe DownloadStatus.Completed
                        state.progress shouldBe Progress.Determinate(absolute.processed, absolute.total)
                    }
                }

                "keeps the terminal progress when a callback arrives after the task was cancelled" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val absolute = Arb.absoluteProgresses().next()
                        lateinit var lateProgress: (SourceProgress) -> Unit
                        val reported = CompletableDeferred<Unit>()
                        world.adapters.single().onDownload = { _, onProgress ->
                            lateProgress = onProgress
                            onProgress(absolute)
                            reported.complete(Unit)
                            CompletableDeferred<Unit>().await()
                            DownloadResult.Success
                        }
                        val handle = world.enqueue(ref)
                        reported.await()
                        handle.cancel()
                        world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }

                        // when
                        lateProgress(SourceProgress.Fraction(0.5))

                        // then
                        val state = world.state(handle.taskId)
                        state.status shouldBe DownloadStatus.Cancelled
                        state.progress shouldBe Progress.Determinate(absolute.processed, absolute.total)
                    }
                }

                "keeps the terminal progress when a callback arrives after the task failed" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val world = facadeWorld(tempDir)
                        world.adapters.single().onFind = { FindResult.Found(mediaId) }
                        val ref = world.resolve(mediaId)
                        val absolute = Arb.absoluteProgresses().next()
                        lateinit var lateProgress: (SourceProgress) -> Unit
                        world.adapters.single().onDownload = { _, onProgress ->
                            lateProgress = onProgress
                            onProgress(absolute)
                            DownloadResult.Failed(DownloadError.NetworkTransient)
                        }
                        val handle = world.enqueue(ref)
                        world.awaitState(handle.taskId) { it.status is DownloadStatus.Failed }

                        // when
                        lateProgress(SourceProgress.Fraction(0.5))

                        // then
                        val state = world.state(handle.taskId)
                        state.status.shouldBeInstanceOf<DownloadStatus.Failed>()
                        state.progress shouldBe Progress.Determinate(absolute.processed, absolute.total)
                    }
                }
            }

            "refuses to enqueue after the queue stopped and does not keep the reservation" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, taskIdGenerator = TaskIdGenerator { TaskId(1) })
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    world.queue.shutdown(5.seconds) shouldBe true

                    // when
                    val refused = shouldThrow<IllegalStateException> { world.enqueue(ref) }

                    // then
                    // the reservation is rolled back, so the next attempt fails on the closed queue again
                    // instead of exhausting the id attempts over a record left in the states map
                    refused.message shouldContain "closed"
                    shouldThrow<IllegalStateException> { world.enqueue(ref) }.message shouldContain "closed"
                    world.adapters.single().downloaded shouldBe emptyList()
                }
            }

            "refuses to enqueue when the parent scope died and does not leave the task in Queued" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)

                    // when the assembly cancels the scope it owns
                    world.parentScope.cancel()

                    // then
                    val refused = shouldThrow<IllegalStateException> { world.enqueue(ref) }
                    refused.message shouldContain "closed"
                    world.adapters.single().downloaded shouldBe emptyList()
                }
            }
        }

        "shutdown" - {

            "cancels the running download and leaves no partial behind" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val target = world.targetPath()
                    val started = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val handle = world.enqueue(ref, target)
                    started.await()
                    world.state(handle.taskId).status shouldBe DownloadStatus.Downloading

                    // when
                    val stopped = world.queue.shutdown(5.seconds)

                    // then
                    stopped shouldBe true
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                    Files.exists(target) shouldBe false
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "cancels the download that waited for a slot" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, initialConfig = AppConfig(maxParallelDownloads = 1))
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val gate = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        gate.await()
                        DownloadResult.Success
                    }
                    val running = world.enqueue(ref)
                    val queued = world.enqueue(ref)
                    world.awaitState(running.taskId) { it.status == DownloadStatus.Downloading }
                    world.state(queued.taskId).status shouldBe DownloadStatus.Queued

                    // when
                    val stopped = world.queue.shutdown(5.seconds)

                    // then
                    stopped shouldBe true
                    world.awaitState(queued.taskId) { it.status == DownloadStatus.Cancelled }
                    world.state(queued.taskId).status shouldBe DownloadStatus.Cancelled
                    world.awaitState(running.taskId) { it.status == DownloadStatus.Cancelled }
                    world.state(running.taskId).status shouldBe DownloadStatus.Cancelled
                }
            }

            "leaves no trace of the stopped session for the next start" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val interrupted = world.targetPath()
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val handle = world.enqueue(ref, interrupted)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Downloading }
                    world.queue.shutdown(5.seconds) shouldBe true

                    // when the assembly builds a fresh core over the same directories
                    val restarted = facadeWorld(world.tempDir)
                    restarted.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val target = restarted.targetPath()
                    val next = restarted.enqueue(restarted.resolve(mediaId), target)

                    // then
                    partialsIn(world.tempDir) shouldBe emptyList()
                    restarted.awaitState(next.taskId) { it.status == DownloadStatus.Completed }
                    restarted.state(next.taskId).status shouldBe DownloadStatus.Completed
                    Files.exists(target) shouldBe true
                }
            }
        }

        "cancel" - {
            "publishes Cancelling while the task is stopping and Cancelled after it stopped" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val stopping = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        try {
                            CompletableDeferred<Unit>().await()
                        } catch (cancelled: CancellationException) {
                            withContext(NonCancellable) { stopping.await() }
                            throw cancelled
                        }
                        DownloadResult.Success
                    }
                    val target = world.targetPath()
                    val handle = world.enqueue(ref, target)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Downloading }

                    // when
                    handle.cancel()
                    val whileStopping = world.state(handle.taskId).status
                    stopping.complete(Unit)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }

                    // then
                    whileStopping shouldBe DownloadStatus.Cancelling
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                    Files.exists(target) shouldBe false
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "does not move the file when the adapter survives the cancellation request" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        withContext(NonCancellable) { release.await() }
                        DownloadResult.Success
                    }
                    val target = world.targetPath()
                    val handle = world.enqueue(ref, target)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Downloading }

                    // when
                    handle.cancel()
                    release.complete(Unit)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }

                    // then
                    // the adapter ignored the cancellation and returned success, but the cancel command was
                    // received before the move: the finished file must not appear and the task stays cancelled
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                    Files.exists(target) shouldBe false
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "keeps Failed when the cancellation completes after the adapter broke" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        withContext(NonCancellable) { release.await() }
                        error("adapter broke")
                    }
                    val target = world.targetPath()
                    val handle = world.enqueue(ref, target)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Downloading }

                    // when
                    handle.cancel()
                    release.complete(Unit)
                    world.awaitState(handle.taskId) { it.status is DownloadStatus.Failed }

                    // then
                    // the adapter broke after the cancellation request, so the failure is the outcome;
                    // the Cancelled of the completion handler must not overwrite it
                    val failed = world.state(handle.taskId).status as DownloadStatus.Failed
                    failed.error shouldBe DownloadError.ExtractorBroken
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "keeps the task Failed when the cancel command arrives after the adapter broke" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    world.adapters.single().onDownload = { _, _ ->
                        DownloadResult.Failed(DownloadError.NetworkTransient)
                    }
                    val ref = world.resolve(mediaId)
                    val handle = world.enqueue(ref)
                    val broken = DownloadStatus.Failed(DownloadError.NetworkTransient)
                    world.state(handle.taskId).status shouldBe broken

                    // when
                    handle.cancel()

                    // then
                    world.state(handle.taskId).status shouldBe broken
                }
            }

            "keeps Cancelled when the cancel command arrives again after the task stopped" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val handle = world.enqueue(ref)
                    handle.cancel()
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }

                    // when
                    handle.cancel()

                    // then
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                }
            }

            "cancels a task that has not started yet and leaves nothing behind" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val target = world.targetPath()
                    val handle = world.enqueue(ref, target)

                    // when
                    handle.cancel()

                    // then
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelling
                    testScheduler.advanceUntilIdle()
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                    world.adapters.single().downloaded shouldBe emptyList()
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "keeps the task Completed when the cancel command arrives after the work is done" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val handle = world.enqueue(ref)
                    world.state(handle.taskId).status shouldBe DownloadStatus.Completed

                    // when
                    handle.cancel()

                    // then
                    world.state(handle.taskId).status shouldBe DownloadStatus.Completed
                }
            }

            "leaves the other task of the same source untouched" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val cancelledStarted = CompletableDeferred<Unit>()
                    val survivorStarted = CompletableDeferred<Unit>()
                    val releaseSurvivor = CompletableDeferred<Unit>()
                    var downloads = 0
                    world.adapters.single().onDownload = { _, _ ->
                        if (downloads++ == 0) {
                            cancelledStarted.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        } else {
                            survivorStarted.complete(Unit)
                            releaseSurvivor.await()
                        }
                        DownloadResult.Success
                    }
                    val cancelled = world.enqueue(ref)
                    cancelledStarted.await()

                    // when
                    val survivor = world.enqueue(ref)
                    survivorStarted.await()
                    cancelled.cancel()
                    world.awaitState(cancelled.taskId) { it.status == DownloadStatus.Cancelled }

                    // then
                    world.state(survivor.taskId).status shouldBe DownloadStatus.Downloading
                    releaseSurvivor.complete(Unit)
                    world.awaitState(survivor.taskId) { it.status == DownloadStatus.Completed }
                    world.state(cancelled.taskId).status shouldBe DownloadStatus.Cancelled
                    world.state(survivor.taskId).status shouldBe DownloadStatus.Completed
                }
            }

            "cancels a waiting task and lets the task behind it start when a slot frees up" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(
                        tempDir,
                        initialConfig = AppConfig(maxParallelDownloads = 1),
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val gate = CompletableDeferred<Unit>()
                    var started = 0
                    world.adapters.single().onDownload = { _, _ ->
                        started++
                        gate.await()
                        DownloadResult.Success
                    }
                    val first = world.enqueue(ref)
                    val second = world.enqueue(ref)
                    val third = world.enqueue(ref)
                    testScheduler.advanceUntilIdle()
                    started shouldBe 1

                    // when
                    second.cancel()
                    testScheduler.advanceUntilIdle()

                    // then
                    world.state(second.taskId).status shouldBe DownloadStatus.Cancelled
                    started shouldBe 1
                    gate.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    world.state(first.taskId).status shouldBe DownloadStatus.Completed
                    world.state(third.taskId).status shouldBe DownloadStatus.Completed
                    started shouldBe 2
                }
            }
        }

        "config" - {

            "starts the tasks that waited on the old limit" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(
                        tempDir,
                        initialConfig = AppConfig(maxParallelDownloads = 1),
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val gate = CompletableDeferred<Unit>()
                    var started = 0
                    world.adapters.single().onDownload = { _, _ ->
                        started++
                        gate.await()
                        DownloadResult.Success
                    }
                    val handles = (1..3).map { world.enqueue(ref) }
                    testScheduler.advanceUntilIdle()
                    started shouldBe 1

                    // when
                    world.config.value = AppConfig(maxParallelDownloads = 3)
                    testScheduler.advanceUntilIdle()

                    // then
                    started shouldBe 3
                    gate.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    handles.forEach { world.state(it.taskId).status shouldBe DownloadStatus.Completed }
                }
            }
        }
    })

private class FacadeWorld(
    rootDir: Path,
    val adapters: List<FakeSourceAdapter>,
    val config: MutableStateFlow<AppConfig>,
    val parentScope: CoroutineScope,
    val queue: DownloadQueue,
    val facade: CoreFacade,
) {
    val tempDir: Path = Files.createTempDirectory(rootDir, "case")

    private val witness = CoroutineScope(Dispatchers.Unconfined)
    private val observed = mutableMapOf<TaskId, MutableList<DownloadStatus>>()
    private val latest = mutableMapOf<TaskId, DownloadState>()
    private var targets = 0

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
private fun facadeWorld(
    tempDir: Path,
    adapters: List<FakeSourceAdapter> = listOf(FakeSourceAdapter()),
    initialConfig: AppConfig = AppConfig(),
    taskIdGenerator: TaskIdGenerator = RandomTaskIdGenerator,
    clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    workContext: CoroutineContext = dispatcher,
): FacadeWorld {
    val scope = CoroutineScope(dispatcher)
    val config = MutableStateFlow(initialConfig)
    val queue = DownloadQueue(scope, dispatcher, workContext, config)
    val facade = CoreFacade(adapters, queue, taskIdGenerator, clock)
    return FacadeWorld(tempDir, adapters, config, scope, queue, facade)
}

private fun leftoverFilesIn(dir: Path, expected: Path): List<Path> = Files.newDirectoryStream(dir).use { entries ->
    entries.filter {
        it !=
            expected
    }
}

private fun partialsIn(dir: Path): List<Path> = Files.newDirectoryStream(dir).use { entries ->
    entries.filter { it.fileName.toString().contains(".part-") }
}
