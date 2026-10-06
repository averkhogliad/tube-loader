package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.textBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RutubeErrorTaxonomyTest :
    FreeSpec({

        "loadMeta" - {
            "reports NotFound when the source answers 404 for a video that does not exist" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, recordedStub("rutube/playOptions-notfound.json", 404))

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.NotFound
            }

            "reports NotFound when the source answers 244 for a video that does not exist" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, recordedStub("rutube/playOptions-notfound.json", 244))

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
                val http =
                    FakeHttpTool()
                        .route(OPTIONS_URL, recordedStub(RECORDING_OPTIONS))
                        .route(MASTER_URL, recordedStub("rutube/m3u8-master-no-quality.m3u8"))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("taxonomy")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "reports ExtractorBroken when the master playlist is empty" {
                // given
                val http =
                    FakeHttpTool()
                        .route(OPTIONS_URL, recordedStub(RECORDING_OPTIONS))
                        .route(MASTER_URL, recordedStub("rutube/m3u8-master-empty.m3u8"))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("taxonomy")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "reports ExtractorBroken when a segment answers 404" {
                // given
                val http =
                    FakeHttpTool()
                        .route(OPTIONS_URL, recordedStub(RECORDING_OPTIONS))
                        .route(MASTER_URL, recordedStub("rutube/m3u8-master.m3u8"))
                        .route(VARIANT_1080, recordedStub("rutube/m3u8-leaf.m3u8"))
                        .route(SEGMENT_1, recordedStubWith("gone", 404))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("taxonomy")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }

            "repeats a rate limit answer and reports NetworkTransient once the attempts run out" {
                // given
                val http =
                    FakeHttpTool()
                        .route(OPTIONS_URL, recordedStub(RECORDING_OPTIONS))
                        .route(MASTER_URL, recordedStub("rutube/m3u8-master.m3u8"))
                        .route(VARIANT_1080, recordedStub("rutube/m3u8-leaf.m3u8"))
                        .route(SEGMENT_1, HttpStub.Respond(textBody("slow down", status = 429)))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("taxonomy")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
                http.opened.count { it.url == SEGMENT_1 } shouldBe 5
            }
        }
    })
