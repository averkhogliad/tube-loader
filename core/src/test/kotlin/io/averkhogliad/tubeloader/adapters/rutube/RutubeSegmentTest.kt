package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.httpBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

class RutubeSegmentTest :
    FreeSpec({

        "download" - {
            "reads every segment of the playlist, in order" {
                // given
                val http = streaming()

                // when
                adapter(http).download(MEDIA_ID, VIDEO_1080, clip("segments")) {}

                // then
                http.opened.map { it.url }.filter { it.startsWith(SEGMENT_BASE) } shouldBe
                    listOf(SEGMENT_1, SEGMENT_2, SEGMENT_3)
            }

            "reports a fraction of the downloaded segments after each of them" {
                // given
                val progress = mutableListOf<SourceProgress>()

                // when
                adapter(streaming()).download(MEDIA_ID, VIDEO_1080, clip("segments")) { progress += it }

                // then
                progress.take(5) shouldBe
                    listOf(
                        SourceProgress.Indeterminate,
                        SourceProgress.Indeterminate,
                        SourceProgress.Fraction(1.0 / 3),
                        SourceProgress.Fraction(2.0 / 3),
                        SourceProgress.Fraction(1.0),
                    )
            }

            "leaves no tmp file when a segment fails" {
                // given
                val http = streaming().route(SEGMENT_2, HttpStub.Fail(IOException("reset")))
                val dir = workDir("segments")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
                Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
            }

            "closes the response of a segment that answers with a client error" {
                // given
                val refused = trackedBody("gone", 404)
                val http = streaming().route(SEGMENT_2, refused.response)

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("segments")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
                refused.isClosed shouldBe true
            }

            "returns NetworkTransient when a segment answers with a server error" {
                // given
                val http = streaming().route(SEGMENT_2, HttpStub.Respond(httpBody("boom".toByteArray(), status = 503)))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("segments")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
            }

            "leaves no tmp file behind when the transfer is cancelled" {
                // given
                val dir = workDir("segments")

                // when
                val thrown =
                    runCatching {
                        adapter(streaming()).download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {
                            throw CancellationException("cancelled by the test")
                        }
                    }.exceptionOrNull()

                // then
                thrown.shouldBeInstanceOf<CancellationException>()
                Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
            }
        }
    })
