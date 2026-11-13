package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
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
import kotlin.time.Duration.Companion.seconds

class RutubePlaylistTest :
    FreeSpec({

        "download" - {
            "walks from the metadata request down to the segments of the chosen quality" {
                // given
                val http = streaming()

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("playlist")) {}

                // then
                actual shouldBe DownloadResult.Success
                http.opened.map { it.url }.take(3) shouldBe listOf(OPTIONS_URL, MASTER_URL, VARIANT_1080)
            }

            "returns ExtractorBroken when the master playlist carries no quality marker" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, RECORDING_OPTIONS)
                        .routeRecording(MASTER_URL, "rutube/m3u8-master-no-quality.m3u8")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("playlist")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "returns ExtractorBroken when the master playlist is empty" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, RECORDING_OPTIONS)
                        .routeRecording(MASTER_URL, "rutube/m3u8-master-empty.m3u8")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("playlist")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "returns ExtractorBroken when the chosen playlist carries no segments" {
                // given
                val http =
                    FakeHttpTool()
                        .routeRecording(OPTIONS_URL, RECORDING_OPTIONS)
                        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
                        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf-empty.m3u8")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("playlist")) {}

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

                // when
                adapter(http).download(MEDIA_ID, VIDEO_1080, clip("playlist")) {}

                // then
                http.opened[1].url shouldBe MASTER_URL
            }

            "prefers the quality of the requested id over a closer-lower one" {
                // given
                val http = streaming()
                val quality = Quality("720p", TrackKind.Video, "720p")

                // when
                adapter(http).download(MEDIA_ID, quality, clip("playlist")) {}

                // then
                http.opened[2].url shouldBe
                    "https://river-1.rutube.ru/hls-vod/720/0x720.mp4.m3u8?i=1280x720_974"
            }

            "does not write the target itself, leaving finalization to the core" {
                // given
                // a remux stub that writes nothing: the file the core would stage must not appear
                val file = clip("playlist")

                // when
                RutubeSourceAdapter(streaming(), FakeMediaTool(), { HttpToolConfig(perAttemptTimeout = 30.seconds) })
                    .download(MEDIA_ID, VIDEO_1080, file) {}

                // then
                Files.exists(file) shouldBe false
            }

            "returns ExtractorBroken when the metadata request itself fails" {
                // given
                val http = FakeHttpTool().always(HttpStub.Respond(httpBody("nope".toByteArray(), status = 404)))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("playlist")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }
        }
    })
