package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.absoluteProgresses
import io.averkhogliad.tubeloader.core.download.fractions
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeEnqueueStagingTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "enqueue" - {
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
        }
    })
