package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.qualities
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.partialsIn
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UseCaseShutdownTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "shutdown" - {

            "reports that the queue stopped within the budget" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    val shutdown = UseCaseShutdown(world.queue::shutdown)

                    // when
                    val stopped = shutdown.shutdown(5.seconds)

                    // then
                    stopped shouldBe true
                }
            }

            "reports that the work outlived the budget" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    val release = CompletableDeferred<Unit>()
                    world.adapters.single().onDownload = { _, _ ->
                        withContext(NonCancellable) { release.await() }
                        DownloadResult.Success
                    }
                    val ref = world.resolve(mediaIds.next())
                    world.enqueue(ref)
                    testScheduler.advanceUntilIdle()

                    // when
                    val stopped = UseCaseShutdown(world.queue::shutdown).shutdown(50.milliseconds)

                    // then
                    stopped shouldBe false

                    // the work outlived the budget and ends only when it lets go on its own
                    release.complete(Unit)
                    testScheduler.advanceUntilIdle()
                }
            }

            "cancels the download that was running when the shutdown came" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    world.adapters.single().onDownload = { _, _ ->
                        CompletableDeferred<Unit>().await()
                        DownloadResult.Success
                    }
                    val ref = world.resolve(mediaIds.next())
                    val target = world.targetPath()
                    val handle = world.enqueue(ref, target)
                    testScheduler.advanceUntilIdle()

                    // when
                    val stopped = UseCaseShutdown(world.queue::shutdown).shutdown(5.seconds)

                    // then
                    stopped shouldBe true
                    world.awaitState(handle.taskId) { it.status == DownloadStatus.Cancelled }
                    world.state(handle.taskId).status shouldBe DownloadStatus.Cancelled
                    Files.exists(target) shouldBe false
                    partialsIn(world.tempDir) shouldBe emptyList()
                }
            }

            "refuses a download that is enqueued after the shutdown" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir, dispatcher = StandardTestDispatcher(testScheduler))
                    val ref = world.resolve(mediaIds.next())
                    UseCaseShutdown(world.queue::shutdown).shutdown(5.seconds) shouldBe true

                    // when
                    val failure =
                        shouldThrow<IllegalStateException> {
                            world.facade.enqueue(ref, Arb.qualities().next(), world.targetPath())
                        }

                    // then
                    failure.message shouldContain "closed"
                }
            }
        }
    })
