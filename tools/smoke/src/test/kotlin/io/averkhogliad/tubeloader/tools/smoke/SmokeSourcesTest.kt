package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.download.DownloadQueue
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val HOME = Path.of("home")

private fun sources(): Map<String, SourceAdapter> =
    smokeSources(
        FakeHttpTool(),
        FakeMediaTool(),
        HttpToolConfig(perAttemptTimeout = 60.seconds),
        YtDlpConfig(paths = ToolPaths.of(HOME)),
    )

/**
 * A facade over the sources of the tool: the codes of the sources are the ones the tool registers,
 * not a copy of them.
 */
private fun <T> withFacade(block: (CoreFacade) -> T): T {
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
        val config = MutableStateFlow(AppConfig(httpTool = HttpToolConfig(perAttemptTimeout = 60.seconds)))
        val queue = DownloadQueue(scope, Dispatchers.Unconfined, Dispatchers.Unconfined, config)
        return block(CoreFacade(sources(), queue, RandomTaskIdGenerator, Clock.System))
    } finally {
        scope.cancel()
    }
}

class SmokeSourcesTest :
    FreeSpec({

        "smokeSources" - {
            "holds the adapter of every source the tool knows, under its code" {
                // given
                val adapters = sources()

                // when
                val codes = adapters.keys.toList()

                // then
                codes shouldBe listOf("rutube", "youtube")
            }

            "names the source of every adapter" {
                // given
                val adapters = sources()

                // when
                val names = adapters.values.map { it.displayName }

                // then
                names shouldBe listOf("Rutube", "YouTube")
            }

            "the youtube adapter answers a youtube url with the media id" {
                // given
                val youtube = sources().getValue("youtube")

                // when
                val actual = runBlocking { youtube.resolveId("https://www.youtube.com/watch?v=abc123") }

                // then
                actual shouldBe ResolveIdResult.Found("abc123")
            }
        }

        "the facade of the tool" - {
            "knows the codes of the sources of the tool" {
                // when
                val codes = withFacade { facade -> facade.availableSources.map { it.code } }

                // then
                codes shouldBe listOf("rutube", "youtube")
            }
        }

        "requireKnownSourceCodes" - {
            "passes when every code of the targets is known" {
                // given
                val targets = listOf(SmokeTarget.ById("rutube", "abc", "clip"))

                // when + then
                withFacade { facade ->
                    requireKnownSourceCodes(targets, facade.availableSources.map { it.code })
                }
            }

            "passes when the targets name no source at all" {
                // given
                val targets = listOf(SmokeTarget.ByUrl("https://rutube.ru/video/abc/", "clip"))

                // when + then
                withFacade { facade ->
                    requireKnownSourceCodes(targets, facade.availableSources.map { it.code })
                }
            }

            "fails with the list of the known codes when a code is unknown" {
                // given
                val targets = listOf(SmokeTarget.ById("nowhere", "abc", "clip"))

                // when
                val failure =
                    shouldThrow<IllegalStateException> {
                        withFacade { facade ->
                            requireKnownSourceCodes(targets, facade.availableSources.map { it.code })
                        }
                    }

                // then
                failure.message!! shouldContain "nowhere"
                failure.message!! shouldContain "rutube"
                failure.message!! shouldContain "youtube"
            }
        }
    })
