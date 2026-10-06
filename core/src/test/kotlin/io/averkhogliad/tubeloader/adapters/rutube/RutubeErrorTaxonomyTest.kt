package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"
private const val OPTIONS_URL =
    "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val MASTER_URL = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
private const val VARIANT_1080 = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"
private const val SEGMENT_1 = "https://segments.rutube.ru/0x1080.mp4/segment-1-v1-a1.ts"

private val VIDEO_1080 = Quality("1080p", TrackKind.Video, "1080p")

private fun adapter(http: FakeHttpTool) = RutubeSourceAdapter(http, FakeMediaTool().copyStreams())

private fun workDir() = Files.createTempDirectory("rutube-taxonomy")

private fun missingVideo(status: Int) =
    FakeHttpTool().route(OPTIONS_URL, recordedStub("playOptions-notfound.json", status))

private fun brokenMaster(resource: String) =
    FakeHttpTool()
        .route(OPTIONS_URL, recordedStub("playOptions-download.json"))
        .route(MASTER_URL, recordedStub(resource))

class RutubeErrorTaxonomyTest :
    FreeSpec({

        "loadMeta" - {
            "reports NotFound when the source answers 404 for a video that does not exist" {
                // given
                val http = missingVideo(404)

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.NotFound
            }

            "reports NotFound when the source answers 244 for a video that does not exist" {
                // given
                val http = missingVideo(244)

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.NotFound
            }

            "reports NetworkTransient once the retry budget of server errors runs out" {
                // given
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
            }
        }

        "download" - {
            "reports ExtractorBroken when the master playlist names no quality marker" {
                // given
                val http = brokenMaster("m3u8-master-no-quality.m3u8")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "reports ExtractorBroken when the master playlist is empty" {
                // given
                val http = brokenMaster("m3u8-master-empty.m3u8")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "reports ExtractorBroken when a segment answers 404" {
                // given
                val http =
                    FakeHttpTool()
                        .route(OPTIONS_URL, recordedStub("playOptions-download.json"))
                        .route(MASTER_URL, recordedStub("m3u8-master.m3u8"))
                        .route(VARIANT_1080, recordedStub("m3u8-leaf.m3u8"))
                        .route(SEGMENT_1, recordedStubWith("gone", 404))
                val target = workDir().resolve("clip.mp4")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, target) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }
        }
    })
