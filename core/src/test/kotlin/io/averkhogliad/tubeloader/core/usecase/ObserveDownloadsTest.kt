package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import io.averkhogliad.tubeloader.core.download.qualities
import io.averkhogliad.tubeloader.core.facade.FacadeSettings
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
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
class ObserveDownloadsTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "states" - {
            "emits an empty list while nothing is tracked" {
                runTest {
                    // given
                    val observe = ObserveDownloads(DownloadTracker())

                    // when
                    val actual = observe.states().first()

                    // then
                    actual shouldBe emptyList()
                }
            }

            "orders the tracked downloads by the moment they were queued" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir, FacadeSettings(clock = steppingClock()))
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        DownloadResult.Success
                    }
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    val observe = ObserveDownloads(tracker)

                    // when
                    val queued =
                        (1..3).map {
                            enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                        }

                    // then
                    val actual = observe.states().first()
                    actual.map { it.taskId } shouldBe queued.map { it.taskId }
                    actual.map { it.startedAt } shouldBe actual.map { it.startedAt }.sorted()
                }
            }

            "breaks ties on equal queue moments by task id" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
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
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        DownloadResult.Success
                    }
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    val observe = ObserveDownloads(tracker)

                    // when
                    repeat(3) {
                        enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    }

                    // then
                    val actual = observe.states().first()
                    actual.map { it.taskId } shouldBe listOf(TaskId(3), TaskId(7), TaskId(9))
                    actual.map { it.startedAt } shouldBe List(3) { queuedAt }
                }
            }

            "shows the new state when a download changes its status" {
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
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    val observe = ObserveDownloads(tracker)
                    val handle = enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()
                    val seen = mutableListOf<List<DownloadState>>()

                    // when
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            observe
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

            "keeps terminal downloads in the list" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(clock = steppingClock()),
                            dispatcher = StandardTestDispatcher(testScheduler),
                        )
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    val observe = ObserveDownloads(tracker)

                    // when
                    val first = enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    val second = enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    testScheduler.advanceUntilIdle()

                    // then
                    val actual = observe.states().first()
                    actual.map { it.taskId } shouldBe listOf(first.taskId, second.taskId)
                    actual.map { it.status } shouldBe
                        listOf(DownloadStatus.Completed, DownloadStatus.Completed)
                }
            }
        }
    })
