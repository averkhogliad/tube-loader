package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.Source
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.facade.MediaResult
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Composition check: the real adapter is reachable through the core facade by the source code, and a
 * media id is not read as a url on the way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RutubeFacadeFindTest :
    FreeSpec({

        "findById" - {
            "returns the media of the named source and does not read the id as a url" {
                runTest {
                    // given
                    val settings = HttpToolConfig(perAttemptTimeout = 30.seconds)
                    val adapter = RutubeSourceAdapter(rutubeRecordedHttp(), FakeMediaTool()) { settings }
                    val queue =
                        DownloadQueue(
                            backgroundScope,
                            UnconfinedTestDispatcher(testScheduler),
                            UnconfinedTestDispatcher(testScheduler),
                            MutableStateFlow(AppConfig(httpTool = settings)),
                        )
                    val facade = CoreFacade(mapOf("rutube" to adapter), queue, RandomTaskIdGenerator, Clock.System)

                    // when
                    val actual = facade.findById("rutube", MEDIA_ID)

                    // then
                    val found = actual.shouldBeInstanceOf<MediaResult.Found>()
                    found.ref shouldBe MediaRef(Source("rutube", "Rutube"), MEDIA_ID)
                    found.meta.id shouldBe MEDIA_ID
                }
            }
        }
    })
