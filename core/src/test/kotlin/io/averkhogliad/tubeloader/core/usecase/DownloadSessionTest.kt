package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import io.averkhogliad.tubeloader.core.download.mediaMetas
import io.averkhogliad.tubeloader.core.download.qualities
import io.averkhogliad.tubeloader.core.facade.FacadeSettings
import io.averkhogliad.tubeloader.core.facade.MediaResult
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.inputs
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.averkhogliad.tubeloader.core.facade.testHttpTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val queuedAt: Instant = Instant.parse("2026-09-30T10:00:00Z")

private fun steppingClock(): Clock {
    var tick = 0
    return object : Clock {
        override fun now(): Instant = queuedAt + 1.minutes * tick++
    }
}

private fun fixedClock(): Clock =
    object : Clock {
        override fun now(): Instant = queuedAt
    }

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSessionTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "states" - {
            "emits an empty list while nothing is enqueued" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    val session = DownloadSession(world.facade)

                    // when
                    val actual = session.states().first()

                    // then
                    actual shouldBe emptyList()
                }
            }

            "shows the download registered by enqueue" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val handle = session.enqueue(ref, Arb.qualities().next(), world.targetPath())

                    // then
                    session.states().first().map { it.taskId } shouldBe listOf(handle.taskId)
                }
            }

            "shows a download enqueued after the observation started" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())
                    val seen = mutableListOf<List<DownloadState>>()
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            session.states().onEach { seen += it }.first { states -> states.isNotEmpty() }
                        }
                    testScheduler.advanceUntilIdle()
                    seen shouldBe listOf(emptyList<DownloadState>())

                    // when
                    val handle = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // then
                    pending.await().map { it.taskId } shouldBe listOf(handle.taskId)
                }
            }

            "orders the downloads by the moment they were queued" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, FacadeSettings(clock = steppingClock()))
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val queued =
                        (1..3).map {
                            session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                        }

                    // then
                    val actual = session.states().first()
                    actual.map { it.taskId } shouldBe queued.map { it.taskId }
                    actual.map { it.startedAt } shouldBe actual.map { it.startedAt }.sorted()
                }
            }

            "breaks ties on equal queue moments by task id" {
                runTest {
                    // given
                    val generated = listOf(TaskId(9), TaskId(3), TaskId(7))
                    var index = 0
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(
                                taskIdGenerator = TaskIdGenerator { generated[index++] },
                                clock = fixedClock(),
                            ),
                        )
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())

                    // when
                    repeat(3) {
                        session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    }

                    // then
                    val actual = session.states().first()
                    actual.map { it.taskId } shouldBe listOf(TaskId(3), TaskId(7), TaskId(9))
                    actual.map { it.startedAt } shouldBe List(3) { queuedAt }
                }
            }

            "shows the new state when a download changes its status" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val handle =
                        session.enqueue(
                            world.resolve(mediaIds.next()),
                            Arb.qualities().next(),
                            world.targetPath(),
                        )
                    testScheduler.advanceUntilIdle()
                    val seen = mutableListOf<List<DownloadState>>()

                    // when
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            session
                                .states()
                                .onEach { seen += it }
                                .first { states -> states.any { it.status == DownloadStatus.Completed } }
                        }
                    testScheduler.advanceUntilIdle()
                    release.complete(Unit)
                    testScheduler.advanceUntilIdle()

                    // then
                    val observed = pending.await()
                    seen.first().single().status shouldBe DownloadStatus.Downloading
                    observed.single().taskId shouldBe handle.taskId
                    observed.single().status shouldBe DownloadStatus.Completed
                }
            }

            "shows progress updates without changing the status" {
                runTest {
                    // given
                    val progress = Progress.Absolute(processed = 1, total = 2)
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onDownload = { _, onProgress ->
                        onProgress(progress)
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)

                    // when
                    session.enqueue(world.resolve(mediaIds.next()), Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual = session.states().first().single()
                    actual.status shouldBe DownloadStatus.Downloading
                    actual.progress shouldBe progress
                }
            }

            "keeps one entry per download while its state changes" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val handle =
                        session.enqueue(
                            world.resolve(mediaIds.next()),
                            Arb.qualities().next(),
                            world.targetPath(),
                        )
                    val seen = mutableListOf<List<DownloadState>>()

                    // when
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            session
                                .states()
                                .onEach { seen += it }
                                .first { states -> states.any { it.status == DownloadStatus.Completed } }
                        }
                    testScheduler.advanceUntilIdle()
                    release.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    pending.await()

                    // then
                    seen.forEach { states -> states.map { it.taskId }.distinct() shouldBe states.map { it.taskId } }
                    seen.last().single().taskId shouldBe handle.taskId
                }
            }

            "keeps a completed download in the list" {
                runTest {
                    // given
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(clock = steppingClock()),
                            dispatcher = StandardTestDispatcher(testScheduler),
                        )
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val first = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    val second = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual = session.states().first()
                    actual.map { it.taskId } shouldBe listOf(first.taskId, second.taskId)
                    actual.map { it.status } shouldBe
                        listOf(DownloadStatus.Completed, DownloadStatus.Completed)
                }
            }

            "keeps a failed download in the list" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onDownload = { _, _ ->
                        DownloadResult.Failed(DownloadError.NetworkTransient, null)
                    }
                    val session = DownloadSession(world.facade)

                    // when
                    val handle =
                        session.enqueue(
                            world.resolve(mediaIds.next()),
                            Arb.qualities().next(),
                            world.targetPath(),
                        )
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual = session.states().first().single()
                    actual.taskId shouldBe handle.taskId
                    actual.status shouldBe DownloadStatus.Failed(DownloadError.NetworkTransient, null)
                }
            }

            "keeps a terminal download when a new one arrives" {
                runTest {
                    // given
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(clock = steppingClock()),
                            dispatcher = StandardTestDispatcher(testScheduler),
                        )
                    val gate = CompletableDeferred<Unit>()
                    var downloads = 0
                    world.adapters.single().onDownload = { _, _ ->
                        if (downloads++ == 0) {
                            DownloadResult.Success
                        } else {
                            gate.await()
                            DownloadResult.Success
                        }
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())
                    val completed = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // when
                    val running = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual = session.states().first()
                    actual.map { it.taskId } shouldBe listOf(completed.taskId, running.taskId)
                    actual.map { it.status } shouldBe listOf(DownloadStatus.Completed, DownloadStatus.Downloading)
                }
            }

            "keeps the queue order when a later download finishes first" {
                runTest {
                    // given
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(clock = steppingClock()),
                            dispatcher = StandardTestDispatcher(testScheduler),
                        )
                    val gate = CompletableDeferred<Unit>()
                    var downloads = 0
                    world.adapters.single().onDownload = { _, _ ->
                        if (downloads++ == 0) {
                            gate.await()
                            DownloadResult.Success
                        } else {
                            DownloadResult.Success
                        }
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())
                    val first = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    val second = session.enqueue(ref, Arb.qualities().next(), world.targetPath())

                    // when
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual = session.states().first()
                    actual.map { it.taskId } shouldBe listOf(first.taskId, second.taskId)
                    actual.map { it.status } shouldBe listOf(DownloadStatus.Downloading, DownloadStatus.Completed)
                }
            }
        }

        "enqueue" - {
            "passes the caller's quality and target path to the adapter" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    val session = DownloadSession(world.facade)
                    val quality: Quality = Arb.qualities().next()
                    val target = world.targetPath()

                    // when
                    session.enqueue(world.resolve(mediaId), quality, target)

                    // then
                    val request =
                        world.adapters
                            .single()
                            .downloaded
                            .single()
                    request.id shouldBe mediaId
                    request.quality shouldBe quality
                    request.targetPath.parent shouldBe target.parent
                    request.targetPath.fileName.toString() shouldStartWith target.fileName.toString()
                }
            }

            "registers the returned handle so the download can be observed and cancelled" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val handle = session.enqueue(ref, Arb.qualities().next(), world.targetPath())

                    // then
                    session.states().first().map { it.taskId } shouldBe listOf(handle.taskId)
                    session.cancel(handle.taskId) shouldBe true
                }
            }

            "registers nothing when the facade refuses the enqueue" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())
                    world.queue.shutdown(5.seconds) shouldBe true

                    // when
                    val failure =
                        shouldThrow<IllegalStateException> {
                            session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                        }

                    // then
                    failure.message shouldContain "closed"
                    session.states().first() shouldBe emptyList()
                    session.cancel(TaskId(Arb.int().next())) shouldBe false
                }
            }

            "carries a download resolved by ResolveMedia to the completed state" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val meta = Arb.mediaMetas().next()
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onResolveId = { ResolveIdResult.Found(mediaId) }
                    world.adapters.single().onFindById = { LoadMetaResult.Found(meta) }
                    val session = DownloadSession(world.facade)
                    val resolve = ResolveMedia(world.facade)

                    // when
                    val resolved = resolve.byUrl(inputs.next())
                    val ref = resolved.shouldBeInstanceOf<MediaResult.Found>().ref
                    val handle = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual =
                        session.states().first { states ->
                            states.any { it.status == DownloadStatus.Completed }
                        }
                    actual.single().taskId shouldBe handle.taskId
                    actual.single().status shouldBe DownloadStatus.Completed
                }
            }
        }

        "cancel" - {
            "cancels the download registered under the task id" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val handle =
                        session.enqueue(
                            world.resolve(mediaIds.next()),
                            Arb.qualities().next(),
                            world.targetPath(),
                        )
                    testScheduler.advanceUntilIdle()

                    // when
                    val cancelled = session.cancel(handle.taskId)
                    testScheduler.advanceUntilIdle()

                    // then
                    cancelled shouldBe true
                    val actual = session.states().first().single()
                    actual.status shouldBe DownloadStatus.Cancelled
                }
            }

            "returns false for a task id that was never registered" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    val session = DownloadSession(world.facade)
                    val unknown = TaskId(Arb.int().next())

                    // when
                    val cancelled = session.cancel(unknown)

                    // then
                    cancelled shouldBe false
                }
            }

            "shows the cancelled download in the observed states" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val handle =
                        session.enqueue(
                            world.resolve(mediaIds.next()),
                            Arb.qualities().next(),
                            world.targetPath(),
                        )
                    testScheduler.advanceUntilIdle()
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            session.states().first { states ->
                                states.any { it.taskId == handle.taskId && it.status == DownloadStatus.Cancelled }
                            }
                        }
                    testScheduler.advanceUntilIdle()

                    // when
                    val cancelled = session.cancel(handle.taskId)
                    testScheduler.advanceUntilIdle()

                    // then
                    cancelled shouldBe true
                    val actual = pending.await().single()
                    actual.taskId shouldBe handle.taskId
                    actual.status shouldBe DownloadStatus.Cancelled
                }
            }

            "leaves the other downloads of the session untouched" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, FacadeSettings(clock = steppingClock()))
                    val firstGate = CompletableDeferred<Unit>()
                    val secondGate = CompletableDeferred<Unit>()
                    var downloads = 0
                    world.adapters.single().onDownload = { _, _ ->
                        if (downloads++ == 0) firstGate.await() else secondGate.await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())
                    val cancelled = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    val survivor = session.enqueue(ref, Arb.qualities().next(), world.targetPath())

                    // when
                    session.cancel(cancelled.taskId) shouldBe true

                    // then
                    val whileStopping =
                        session.states().first { states -> states.any { it.status == DownloadStatus.Cancelled } }
                    whileStopping.map { it.status } shouldBe
                        listOf(DownloadStatus.Cancelled, DownloadStatus.Downloading)
                    secondGate.complete(Unit)
                    val finished =
                        session.states().first { states -> states.any { it.status == DownloadStatus.Completed } }
                    finished.map { it.status } shouldBe
                        listOf(DownloadStatus.Cancelled, DownloadStatus.Completed)
                    finished.map { it.taskId } shouldBe listOf(cancelled.taskId, survivor.taskId)
                }
            }

            "cancels a waiting download without touching the running one" {
                runTest {
                    // given
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(
                                initialConfig = AppConfig(maxParallelDownloads = 1, httpTool = testHttpTool),
                                clock = steppingClock(),
                            ),
                            dispatcher = StandardTestDispatcher(testScheduler),
                        )
                    val gate = CompletableDeferred<Unit>()
                    var started = 0
                    world.adapters.single().onDownload = { _, _ ->
                        started++
                        gate.await()
                        DownloadResult.Success
                    }
                    val session = DownloadSession(world.facade)
                    val ref = world.resolve(mediaIds.next())
                    val running = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    val waiting = session.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()
                    started shouldBe 1

                    // when
                    val cancelled = session.cancel(waiting.taskId)
                    testScheduler.advanceUntilIdle()

                    // then
                    cancelled shouldBe true
                    val actual = session.states().first()
                    actual.map { it.taskId } shouldBe listOf(running.taskId, waiting.taskId)
                    actual.map { it.status } shouldBe listOf(DownloadStatus.Downloading, DownloadStatus.Cancelled)
                    started shouldBe 1
                }
            }

            "answers true for a terminal download and keeps it terminal" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    val session = DownloadSession(world.facade)
                    val handle =
                        session.enqueue(
                            world.resolve(mediaIds.next()),
                            Arb.qualities().next(),
                            world.targetPath(),
                        )
                    testScheduler.advanceUntilIdle()
                    val actual = session.states().first().single()
                    actual.status shouldBe DownloadStatus.Completed

                    // when
                    val cancelled = session.cancel(handle.taskId)

                    // then
                    cancelled shouldBe true
                    val after = session.states().first().single()
                    after.status shouldBe DownloadStatus.Completed
                }
            }
        }
    })
