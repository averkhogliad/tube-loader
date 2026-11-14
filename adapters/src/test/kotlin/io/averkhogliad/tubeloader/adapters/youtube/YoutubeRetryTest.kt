package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.infra.binaries.ProcessLaunch
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private const val MEDIA_ID = "dQw4w9WgXcQ"

private fun fastSettings(retries: Int = 3): YtDlpConfig =
    YtDlpConfig(
        paths = ToolPaths.of(Path.of("home"), "Linux"),
        binaryPath = Path.of("yt-dlp"),
        retryMaxAttempts = retries,
        retryBaseDelay = 1.milliseconds,
    )

/** A tool that hands out the replies in order, one per launch, and fails when it runs out. */
private class SequencedReplies(private val replies: List<ToolReply>) {

    private val launches = mutableListOf<ProcessLaunch>()

    fun launches(): List<ProcessLaunch> = launches.toList()

    fun replyOf(): (ProcessLaunch) -> ToolReply =
        { launch ->
            launches += launch
            replies[launches.size - 1]
        }
}

class YoutubeRetryTest :
    FreeSpec({

        "findById retries" - {

            "retries a transient network failure and succeeds on the next attempt" {
                // given
                val seq =
                    SequencedReplies(
                        listOf(
                            ToolReply(exitedWith = 1, error = "ERROR: Unable to download webpage: timed out"),
                            ToolReply(output = goldenText("meta.json")),
                        ),
                    )
                val adapter = adapterOver(fastSettings(), seq.replyOf())

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                actual::class shouldBe LoadMetaResult.Found::class
                seq.launches().size shouldBe 2
            }

            "retries an expired address and succeeds on the next attempt" {
                // given
                val seq =
                    SequencedReplies(
                        listOf(
                            ToolReply(exitedWith = 1, error = "ERROR: HTTP Error 403"),
                            ToolReply(output = goldenText("meta.json")),
                        ),
                    )
                val adapter = adapterOver(fastSettings(), seq.replyOf())

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                actual::class shouldBe LoadMetaResult.Found::class
                seq.launches().size shouldBe 2
            }

            "stops at the first verdict for a media the source no longer serves" {
                // given
                val seq =
                    SequencedReplies(
                        listOf(ToolReply(exitedWith = 1, error = "ERROR: Video unavailable")),
                    )
                val adapter = adapterOver(fastSettings(), seq.replyOf())

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NotFound)
                seq.launches().size shouldBe 1
            }

            "stops at the first verdict for the bot check" {
                // given
                val seq =
                    SequencedReplies(
                        listOf(ToolReply(exitedWith = 1, error = "ERROR: Sign in to confirm you're not a bot")),
                    )
                val adapter = adapterOver(fastSettings(), seq.replyOf())

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.AntiBotBlocked)
                seq.launches().size shouldBe 1
            }

            "stops at the first verdict for a broken extractor" {
                // given
                val seq =
                    SequencedReplies(
                        listOf(ToolReply(exitedWith = 1, error = "ERROR: Requested format is not available")),
                    )
                val adapter = adapterOver(fastSettings(), seq.replyOf())

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
                seq.launches().size shouldBe 1
            }
        }
    })
