package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.qualities
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EnqueueDownloadTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "enqueue" - {
            "registers the returned handle so it can be found by its task id" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    val ref = world.resolve(mediaId)
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    val quality: Quality = Arb.qualities().next()

                    // when
                    val handle = enqueue.enqueue(ref, quality, world.targetPath())

                    // then
                    tracker.handle(handle.taskId) shouldBeSameInstanceAs handle
                    tracker.handles.value shouldBe listOf(handle)
                }
            }

            "passes the caller's quality and target path to the adapter" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    val ref = world.resolve(mediaId)
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    val quality: Quality = Arb.qualities().next()
                    val target = world.targetPath()

                    // when
                    enqueue.enqueue(ref, quality, target)

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

            "keeps the handles in registration order" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    val ref = world.resolve(mediaId)
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)

                    // when
                    val first = enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                    val second = enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())

                    // then
                    tracker.handles.value shouldBe listOf(first, second)
                }
            }

            "registers nothing when the facade refuses the enqueue" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    val ref = world.resolve(mediaId)
                    val tracker = DownloadTracker()
                    val enqueue = EnqueueDownload(world.facade, tracker)
                    world.queue.shutdown(5.seconds) shouldBe true

                    // when
                    val failure =
                        shouldThrow<IllegalStateException> {
                            enqueue.enqueue(ref, Arb.qualities().next(), world.targetPath())
                        }

                    // then
                    failure.message shouldContain "closed"
                    tracker.handles.value shouldBe emptyList()
                }
            }
        }

        "tracker" - {
            "returns no handle for a task id that was never registered" {
                // given
                val tracker = DownloadTracker()
                val unknown = TaskId(Arb.int().next())

                // when
                val handle = tracker.handle(unknown)

                // then
                handle shouldBe null
            }
        }
    })
