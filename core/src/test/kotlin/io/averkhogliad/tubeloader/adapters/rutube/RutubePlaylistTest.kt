package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.httpBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"
private const val QUALITY_1080P = "1080p"
private const val OPTIONS_URL =
    "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val MASTER_URL = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
private const val VARIANT_1080 = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"

private val VIDEO_1080 = Quality(QUALITY_1080P, TrackKind.Video, QUALITY_1080P)

private fun stub() =
    FakeHttpTool()
        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf.m3u8")

private fun target() = Files.createTempDirectory("rutube-playlist").resolve("clip.mp4")

class RutubePlaylistTest :
    FreeSpec({

        "download" - {
            "walks from the metadata request down to the segments of the chosen quality" {
                // given
                val http = stub()
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())

                // when
                runCatching { adapter.download(MEDIA_ID, VIDEO_1080, target()) {} }

                // then
                http.opened.map { it.url }.take(3) shouldBe listOf(OPTIONS_URL, MASTER_URL, VARIANT_1080)
            }

            "returns ExtractorBroken when the master playlist carries no quality marker" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
                        .routeRecording(MASTER_URL, "rutube/m3u8-master-no-quality.m3u8")
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())

                // when
                val actual = adapter.download(MEDIA_ID, VIDEO_1080, target()) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "returns ExtractorBroken when the master playlist is empty" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
                        .routeRecording(MASTER_URL, "rutube/m3u8-master-empty.m3u8")
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())

                // when
                val actual = adapter.download(MEDIA_ID, VIDEO_1080, target()) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "returns ExtractorBroken when the chosen playlist carries no segments" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
                        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
                        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf-empty.m3u8")
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())

                // when
                val actual = adapter.download(MEDIA_ID, VIDEO_1080, target()) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "falls back to the default playlist when the balancer carries no master" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, "rutube/playOptions-default-only.json")
                        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
                        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf.m3u8")
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())

                // when
                runCatching { adapter.download(MEDIA_ID, VIDEO_1080, target()) {} }

                // then
                http.opened[1].url shouldBe MASTER_URL
            }

            "prefers the quality of the requested id over a closer-lower one" {
                // given
                val http = stub()
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())
                val quality = Quality("720p", TrackKind.Video, "720p")

                // when
                runCatching { adapter.download(MEDIA_ID, quality, target()) {} }

                // then
                http.opened[2].url shouldBe "https://river-1.rutube.ru/hls-vod/720/0x720.mp4.m3u8?i=1280x720_974"
            }

            "does not write the target itself, leaving finalization to the core" {
                // given
                val http = stub()
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())
                val file = target()

                // when
                runCatching { adapter.download(MEDIA_ID, VIDEO_1080, file) {} }

                // then
                Files.exists(file) shouldBe false
            }

            "returns ExtractorBroken when the metadata request itself fails" {
                // given
                val http = FakeHttpTool().always(HttpStub.Respond(httpBody("nope".toByteArray(), status = 404)))
                val adapter = RutubeSourceAdapter(http, FakeMediaTool())

                // when
                val actual = adapter.download(MEDIA_ID, VIDEO_1080, target()) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }
        }
    })
