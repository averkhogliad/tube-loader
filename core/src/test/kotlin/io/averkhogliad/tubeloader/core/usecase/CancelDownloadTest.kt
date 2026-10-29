package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CancelDownloadTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "cancel" - {
            "cancels the download registered under the task id" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val ref = world.resolve(mediaId)
                    val tracker = DownloadTracker()
                    val handle = world.enqueue(ref)
                    tracker.register(handle)
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Downloading }
                    val cancel = CancelDownload(tracker)

                    // when
                    val cancelled = cancel.cancel(handle.taskId)

                    // then
                    cancelled shouldBe true
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                }
            }

            "returns false for a task id that was never registered" {
                runTest {
                    // given
                    val tracker = DownloadTracker()
                    val cancel = CancelDownload(tracker)
                    val unknown = TaskId(Arb.int().next())

                    // when
                    val cancelled = cancel.cancel(unknown)

                    // then
                    cancelled shouldBe false
                }
            }
        }
    })
