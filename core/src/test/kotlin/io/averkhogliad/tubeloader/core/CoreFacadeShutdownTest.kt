package io.averkhogliad.tubeloader.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeShutdownTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

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
                    val world = facadeWorld(
                        tempDir,
                        FacadeSettings(initialConfig = AppConfig(maxParallelDownloads = 1)),
                    )
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
    })
