package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val HOME = Path.of("home")

private fun sources(): List<SourceAdapter> =
    smokeSources(
        FakeHttpTool(),
        FakeMediaTool(),
        HttpToolConfig(perAttemptTimeout = 60.seconds),
        YtDlpConfig(paths = ToolPaths.of(HOME)),
    )

class SmokeSourcesTest :
    FreeSpec({

        "smokeSources" - {
            "holds the adapter of every source the tool knows" {
                // given
                val adapters = sources()

                // when
                val names = adapters.map { it.displayName }

                // then
                names shouldBe listOf("Rutube", "YouTube")
            }

            "the youtube adapter answers a youtube url with the media id" {
                // given
                val youtube = sources().single { it.displayName == "YouTube" }

                // when
                val actual = runBlocking { youtube.find("https://www.youtube.com/watch?v=abc123") }

                // then
                actual shouldBe FindResult.Found("abc123")
            }
        }
    })
