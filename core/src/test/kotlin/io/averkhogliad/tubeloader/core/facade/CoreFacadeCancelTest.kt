package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeCancelTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

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

            "cancels the task when the adapter throws a CancellationException of its own" {
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
                        throw CancellationException("adapter gave up")
                    }
                    val handle = world.enqueue(ref, target)
                    testScheduler.advanceUntilIdle()

                    // when
                    release.complete(Unit)
                    testScheduler.advanceUntilIdle()

                    // then
                    // a CancellationException ends the coroutine as cancelled whatever threw it, so the
                    // adapter must report its own timeout as a value instead of throwing
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
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
                        FacadeSettings(initialConfig = AppConfig(maxParallelDownloads = 1)),
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
    })
