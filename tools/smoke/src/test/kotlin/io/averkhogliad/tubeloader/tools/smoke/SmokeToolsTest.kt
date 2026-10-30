package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path

private val HOME = Path.of("home")
private val BINARY = Path.of("home", "ffmpeg", "ffmpeg.exe")

class SmokeToolsTest :
    FreeSpec({

        "ensureFfmpeg" - {
            "fetches the tool once per run and answers the binary it found" {
                // given
                var fetches = 0
                val screen = ByteArrayOutputStream()
                val dashboard = ProgressDashboard(PrintStream(screen))
                val tools =
                    SmokeTools(ToolPaths.of(HOME), dashboard) {
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
                val screen = ByteArrayOutputStream()
                val dashboard = ProgressDashboard(PrintStream(screen))
                val failure = IOException("no network")
                val tools = SmokeTools(ToolPaths.of(HOME), dashboard) { Result.failure(failure) }

                // when
                val actual = runBlocking { tools.ensureFfmpeg() }
                dashboard.close()

                // then
                actual.exceptionOrNull() shouldBe failure
                screen.toString() shouldBe "ffmpeg is not available: no network\r\n"
            }
        }
    })
