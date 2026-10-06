package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.textBody
import io.averkhogliad.tubeloader.retry.constantDelay
import io.averkhogliad.tubeloader.retry.plus
import io.averkhogliad.tubeloader.retry.retry
import io.averkhogliad.tubeloader.retry.stopAtAttempts
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class RutubeRetryTest :
    FreeSpec({

        "loadMeta" - {
            "asks again after a server error and answers from the next response" {
                // given
                val http =
                    FakeHttpTool().route(
                        OPTIONS_URL,
                        SERVER_ERROR_STUB,
                        SERVER_ERROR_STUB,
                        recordedStub(RECORDING_OPTIONS),
                    )

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual.shouldBeInstanceOf<LoadMetaResult.Found>()
                http.opened.size shouldBe 3
            }

            "gives up after five attempts and reports a transient failure" {
                // given
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
                http.opened.size shouldBe 5
            }

            "closes every response it discards while retrying" {
                // given
                // three refusals with distinct bodies: each one is thrown away by the retry loop
                val discarded = List(3) { trackedBody("unavailable", 503) }
                val http = FakeHttpTool().route(OPTIONS_URL, *discarded.map { it.response }.toTypedArray())

                // when
                adapter(http).loadMeta(MEDIA_ID)

                // then
                discarded.map { it.isClosed } shouldBe listOf(true, true, true)
            }

            "retries a transport failure" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, TRANSPORT_FAILURE_STUB, recordedStub(RECORDING_OPTIONS))

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual.shouldBeInstanceOf<LoadMetaResult.Found>()
                http.opened.size shouldBe 2
            }

            "does not retry a client error" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, CLIENT_ERROR_STUB, recordedStub(RECORDING_OPTIONS))

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
                http.opened.size shouldBe 1
            }

            "sends the referer the source requires on every attempt" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, CLIENT_ERROR_STUB)

                // when
                adapter(http).loadMeta(MEDIA_ID)

                // then
                http.opened.map { it.headers["Referer"] }.distinct() shouldBe listOf("https://rutube.ru")
            }
        }

        "download" - {
            "recovers when a segment fails once" {
                // given
                val http =
                    streaming()
                        .route(SEGMENT_2, CONNECTION_RESET_STUB, HttpStub.Respond(textBody("SEGMENT-TWO")))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("retry")) {}

                // then
                actual shouldBe DownloadResult.Success
            }

            "gives up on a segment that stays unavailable and reports a transient failure" {
                // given
                val http = streaming().route(SEGMENT_2, SERVER_ERROR_STUB)
                val dir = workDir("retry")

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
                Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
            }

            "gives each segment its own attempts" {
                // given
                // two segments each fail once: attempts shared across the stage would run out on the first
                val http =
                    streaming()
                        .route(SEGMENT_1, CONNECTION_RESET_STUB, HttpStub.Respond(textBody("ONE")))
                        .route(SEGMENT_2, CONNECTION_RESET_STUB, HttpStub.Respond(textBody("TWO")))

                // when
                val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, clip("retry")) {}

                // then
                actual shouldBe DownloadResult.Success
            }

            "breaks the retry wait as soon as the task is cancelled" {
                // given
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)
                val policy = stopAtAttempts<Throwable>(5) + constantDelay<Throwable>(50.milliseconds)

                // when
                val thrown =
                    runCatching {
                        withTimeout(30.milliseconds) {
                            retry<String>(policy) {
                                http.open(OPTIONS_URL, emptyMap())
                                Result.failure(IOException("unreachable"))
                            }
                        }
                    }.exceptionOrNull()

                // then
                thrown.shouldBeInstanceOf<CancellationException>()
            }
        }
    })
