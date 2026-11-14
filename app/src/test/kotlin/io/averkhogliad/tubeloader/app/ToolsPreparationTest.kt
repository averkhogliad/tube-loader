package io.averkhogliad.tubeloader.app

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path

private val FFMPEG = Path.of("home", "ffmpeg", "ffmpeg.exe")
private val YT_DLP = Path.of("home", "yt-dlp", "yt-dlp.exe")

class ToolsPreparationTest :
    FreeSpec({

        "prepare" - {
            "fetches both tools once and answers the cached status on a repeated call" {
                // given
                var ffmpegFetches = 0
                var ytDlpFetches = 0
                val preparation =
                    ToolsPreparation(
                        prepareFfmpeg = {
                            ffmpegFetches++
                            Result.success(FFMPEG)
                        },
                        prepareYtDlp = {
                            ytDlpFetches++
                            Result.success(YT_DLP)
                        },
                    )

                // when
                val first = runBlocking { preparation.prepare { } }
                val second = runBlocking { preparation.prepare { } }

                // then
                ffmpegFetches shouldBe 1
                ytDlpFetches shouldBe 1
                first shouldBe PreparedTools(ToolStatus.Ready(FFMPEG), ToolStatus.Ready(YT_DLP))
                second shouldBe first
            }

            "reports the status of each tool on its own" {
                // given
                val preparation =
                    ToolsPreparation(
                        prepareFfmpeg = { Result.failure(IOException("no network")) },
                        prepareYtDlp = { Result.success(YT_DLP) },
                    )
                val notes = mutableListOf<String>()

                // when
                val actual = runBlocking { preparation.prepare { note -> notes += note } }

                // then
                actual.ffmpeg shouldBe ToolStatus.Unavailable("no network")
                actual.ytDlp shouldBe ToolStatus.Ready(YT_DLP)
                notes shouldBe listOf("ffmpeg is not available: no network")
            }

            "names the reason of the unavailable tool in its note" {
                // given
                val preparation =
                    ToolsPreparation(
                        prepareFfmpeg = { Result.success(FFMPEG) },
                        prepareYtDlp = { Result.failure(IOException("download failed")) },
                    )
                val notes = mutableListOf<String>()

                // when
                val actual = runBlocking { preparation.prepare { note -> notes += note } }

                // then
                actual.ytDlp shouldBe ToolStatus.Unavailable("download failed")
                notes shouldBe listOf("yt-dlp is not available: download failed")
            }

            "does not repeat the notes on a repeated call" {
                // given
                val preparation =
                    ToolsPreparation(
                        prepareFfmpeg = { Result.failure(IOException("no network")) },
                        prepareYtDlp = { Result.success(YT_DLP) },
                    )
                val notes = mutableListOf<String>()

                // when
                runBlocking { preparation.prepare { note -> notes += note } }
                runBlocking { preparation.prepare { note -> notes += note } }

                // then
                notes shouldBe listOf("ffmpeg is not available: no network")
            }

            "names the tool without a reason when the failure carries no message" {
                // given
                val preparation =
                    ToolsPreparation(
                        prepareFfmpeg = { Result.failure(IOException()) },
                        prepareYtDlp = { Result.success(YT_DLP) },
                    )
                val notes = mutableListOf<String>()

                // when
                val actual = runBlocking { preparation.prepare { note -> notes += note } }

                // then
                actual.ffmpeg shouldBe ToolStatus.Unavailable(null)
                notes shouldBe listOf("ffmpeg is not available")
            }
        }
    })
