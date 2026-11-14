package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path

private val FFMPEG = Path.of("home", "ffmpeg", "ffmpeg.exe")
private val YT_DLP = Path.of("home", "yt-dlp", "yt-dlp.exe")

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
                    SmokeTools(
                        screen.dashboard,
                        ensure = {
                            fetches++
                            Result.success(FFMPEG)
                        },
                        ytDlpFetch = { error("not used") },
                    )

                // when
                val first = runBlocking { tools.ensureFfmpeg() }
                val second = runBlocking { tools.ensureFfmpeg() }

                // then
                fetches shouldBe 1
                first.getOrNull() shouldBe FFMPEG
                second.getOrNull() shouldBe FFMPEG
            }

            "reports a tool it cannot obtain as a note and goes on" {
                // given
                val failure = IOException("no network")
                val tools =
                    SmokeTools(
                        screen.dashboard,
                        ensure = { Result.failure(failure) },
                        ytDlpFetch = { error("not used") },
                    )

                // when
                val actual = runBlocking { tools.ensureFfmpeg() }

                // then
                actual.exceptionOrNull() shouldBe failure
                screen.lines() shouldBe listOf("ffmpeg is not available: no network")
            }
        }

        "ensureYtDlp" - {
            "fetches the tool once per run and answers the binary it found" {
                // given
                var fetches = 0
                val tools =
                    SmokeTools(
                        screen.dashboard,
                        ensure = { error("not used") },
                        ytDlpFetch = {
                            fetches++
                            Result.success(YT_DLP)
                        },
                    )

                // when
                val first = runBlocking { tools.ensureYtDlp() }
                val second = runBlocking { tools.ensureYtDlp() }

                // then
                fetches shouldBe 1
                first.getOrNull() shouldBe YT_DLP
                second.getOrNull() shouldBe YT_DLP
            }

            "reports a tool it cannot obtain as a note and goes on" {
                // given
                val failure = IOException("no network")
                val tools =
                    SmokeTools(
                        screen.dashboard,
                        ensure = { error("not used") },
                        ytDlpFetch = { Result.failure(failure) },
                    )

                // when
                val actual = runBlocking { tools.ensureYtDlp() }

                // then
                actual.exceptionOrNull() shouldBe failure
                screen.lines() shouldBe listOf("yt-dlp is not available: no network")
            }
        }
    })
