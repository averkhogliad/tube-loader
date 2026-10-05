package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeEnqueueFailureTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "enqueue" - {
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
                    val thrown = IllegalStateException("adapter broke")
                    world.adapters.single().onDownload = { _, _ ->
                        release.await()
                        throw thrown
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
                    failed.cause shouldBeSameInstanceAs thrown
                    leftoverFilesIn(target.parent, target) shouldBe emptyList()
                }
            }

            "keeps the cause when a local io operation fails" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(
                        tempDir,
                        FacadeSettings(taskIdGenerator = TaskIdGenerator { TaskId(1) }),
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }
                    val ref = world.resolve(mediaId)
                    // a directory where the partial file belongs makes Files.createFile fail
                    val target = world.targetPath()
                    Files.createDirectory(target.resolveSibling("${target.fileName}.part-00000001"))

                    // when
                    val handle = world.enqueue(ref, target)
                    testScheduler.advanceUntilIdle()

                    // then
                    // a local io failure is not an extractor problem, but the M1 error set has no class
                    // for it: the cause carries the real verdict until the set grows
                    val failed = world.state(handle.taskId).status as DownloadStatus.Failed
                    failed.error shouldBe DownloadError.ExtractorBroken
                    failed.cause.shouldBeInstanceOf<IOException>()
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
        }
    })
