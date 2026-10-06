package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeEnqueueTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

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
                    world.observedStatuses(handle.taskId) shouldBe
                        listOf(
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
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(initialConfig = AppConfig(maxParallelDownloads = 1)),
                        )
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
                    val passed =
                        world.adapters
                            .single()
                            .downloaded
                            .single()
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

            "task id allocation" - {
                "keeps both tasks when the generator repeats an occupied id" {
                    runTest {
                        // given
                        val mediaId = mediaIds.next()
                        val generated = listOf(TaskId(1), TaskId(1), TaskId(2))
                        var index = 0
                        val world =
                            facadeWorld(
                                tempDir,
                                FacadeSettings(taskIdGenerator = TaskIdGenerator { generated[index++] }),
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
                        val world =
                            facadeWorld(
                                tempDir,
                                FacadeSettings(taskIdGenerator = TaskIdGenerator { TaskId(1) }),
                            )
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
                        val clock =
                            object : Clock {
                                override fun now(): Instant = moments.removeFirst()
                            }
                        val world = facadeWorld(tempDir, FacadeSettings(clock = clock))
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

            "refuses to enqueue after the queue stopped and does not keep the reservation" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(taskIdGenerator = TaskIdGenerator { TaskId(1) }),
                        )
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
    })
