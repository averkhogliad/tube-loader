package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path

private val HOME = Path.of("home")
private val BINARY = Path.of("home", "ffmpeg", "ffmpeg.exe")

private fun ytDlpConfig(): YtDlpConfig = YtDlpConfig(paths = ToolPaths.of(HOME))

class SmokeToolsTest :
    FreeSpec({

        lateinit var screen: Screen

        beforeEach { screen = Screen() }

        afterEach { screen.close() }

        "ensureFfmpeg" - {
            "fetches the tool once per run and answers the binary it found" {
                // given
                var fetches = 0
                val tools =
                    SmokeTools(ToolPaths.of(HOME), ytDlpConfig(), screen.dashboard) {
                        fetches++
                        Result.success(BINARY)
                    }

                // when
                val first = runBlocking { tools.ensureFfmpeg() }
                val second = runBlocking { tools.ensureFfmpeg() }

                // then
                fetches shouldBe 1
                first.getOrNull() shouldBe BINARY
                second.getOrNull() shouldBe BINARY
            }

            "reports a tool it cannot obtain as a note and goes on" {
                // given
                val failure = IOException("no network")
                val tools = SmokeTools(ToolPaths.of(HOME), ytDlpConfig(), screen.dashboard) { Result.failure(failure) }

                // when
                val actual = runBlocking { tools.ensureFfmpeg() }

                // then
                actual.exceptionOrNull() shouldBe failure
                screen.lines() shouldBe listOf("ffmpeg is not available: no network")
            }
        }
    })
