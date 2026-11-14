package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.textBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class RutubeErrorTaxonomyTest :
    FreeSpec({

        "findById" - {
            "reports NotFound when the source answers 404 for a video that does not exist" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, recordedStub("rutube/playOptions-notfound.json", 404))

                // when
                val actual = adapter(http).findById(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.NotFound
            }

            "reports NotFound when the source answers 244 for a video that does not exist" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, recordedStub("rutube/playOptions-notfound.json", 244))

                // when
                val actual = adapter(http).findById(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.NotFound
            }

            "reports NetworkTransient with the last status as the cause once the attempts run out" {
                // given
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)

                // when
                val actual = adapter(http).findById(MEDIA_ID)

                // then
                val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                failed.error shouldBe DownloadError.NetworkTransient
                val cause = failed.cause.shouldBeInstanceOf<RutubeStatusFailure>()
                cause.status shouldBe 503
                cause.message.orEmpty() shouldContain "HTTP 503"
            }

            "reports NetworkTransient with the broken body as the cause" {
                // given
                val http = FakeHttpTool().always(BROKEN_BODY_STUB)

                // when
                val actual = adapter(http).findById(MEDIA_ID)

                // then
                val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                failed.error shouldBe DownloadError.NetworkTransient
                failed.cause?.message.orEmpty() shouldContain "connection reset mid-body"
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
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken, null)
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
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken, null)
            }

            "reports ExtractorBroken with the refused status of a segment as the cause" {
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
                val failed = actual.shouldBeInstanceOf<DownloadResult.Failed>()
                failed.error shouldBe DownloadError.ExtractorBroken
                val cause = failed.cause.shouldBeInstanceOf<RutubeStatusFailure>()
                cause.status shouldBe 404
                cause.message.orEmpty() shouldContain "HTTP 404"
            }

            "repeats a rate limit answer and reports the last status as the cause" {
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
                val failed = actual.shouldBeInstanceOf<DownloadResult.Failed>()
                failed.error shouldBe DownloadError.NetworkTransient
                val cause = failed.cause.shouldBeInstanceOf<RutubeStatusFailure>()
                cause.status shouldBe 429
                http.opened.count { it.url == SEGMENT_1 } shouldBe 5
            }
        }
    })
