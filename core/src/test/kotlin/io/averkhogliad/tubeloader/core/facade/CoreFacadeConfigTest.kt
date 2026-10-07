package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeConfigTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "config" - {

            "starts the tasks that waited on the old limit" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world =
                        facadeWorld(
                            tempDir,
                            FacadeSettings(
                                initialConfig = AppConfig(maxParallelDownloads = 1, httpTool = testHttpTool),
                            ),
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
                    val handles = (1..3).map { world.enqueue(ref) }
                    testScheduler.advanceUntilIdle()
                    started shouldBe 1

                    // when
                    world.config.value = AppConfig(maxParallelDownloads = 3, httpTool = testHttpTool)
                    testScheduler.advanceUntilIdle()

                    // then
                    started shouldBe 3
                    gate.complete(Unit)
                    testScheduler.advanceUntilIdle()
                    handles.forEach { world.state(it.taskId).status shouldBe DownloadStatus.Completed }
                }
            }
        }
    })
