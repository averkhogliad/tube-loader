package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.textBody
import io.averkhogliad.tubeloader.retry.RetryPolicy
import io.averkhogliad.tubeloader.retry.constantDelay
import io.averkhogliad.tubeloader.retry.retry
import io.averkhogliad.tubeloader.retry.stopAtAttempts
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
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

            "repeats a rate limit answer and answers from the next response" {
                // given
                val http =
                    FakeHttpTool().route(
                        OPTIONS_URL,
                        HttpStub.Respond(textBody("slow down", status = 429)),
                        recordedStub(RECORDING_OPTIONS),
                    )

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

            "does not retry a server error outside the transient set" {
                // given
                // 500 is the verdict of the source, not a hiccup: the default set names only 502/503/504
                val refused = trackedBody("boom", 500)
                val http = FakeHttpTool().route(OPTIONS_URL, refused.response)

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
                http.opened.size shouldBe 1
                refused.isClosed shouldBe true
            }

            "repeats a request timeout answer and answers from the next response" {
                // given
                val http =
                    FakeHttpTool().route(
                        OPTIONS_URL,
                        HttpStub.Respond(textBody("timeout", status = 408)),
                        recordedStub(RECORDING_OPTIONS),
                    )

                // when
                val actual = adapter(http).loadMeta(MEDIA_ID)

                // then
                actual.shouldBeInstanceOf<LoadMetaResult.Found>()
                http.opened.size shouldBe 2
            }

            "sends the referer the source requires on every attempt" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, CLIENT_ERROR_STUB)

                // when
                adapter(http).loadMeta(MEDIA_ID)

                // then
                http.opened.map { it.headers["Referer"] }.distinct() shouldBe listOf("https://rutube.ru")
            }

            "takes the number of attempts from the configuration" {
                // given
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)
                val settings = HttpToolConfig(perAttemptTimeout = 30.seconds, retryMaxAttempts = 2)

                // when
                val actual = adapter(http, settings).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
                http.opened.size shouldBe 2
            }

            "does not repeat a status the configuration leaves out" {
                // given
                val http = FakeHttpTool().route(OPTIONS_URL, HttpStub.Respond(textBody("slow down", status = 429)))
                val settings = HttpToolConfig(perAttemptTimeout = 30.seconds, retryRetriableStatuses = setOf(503))

                // when
                val actual = adapter(http, settings).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
                http.opened.size shouldBe 1
            }

            "reads the configuration on every call rather than once" {
                // given
                var attempts = 1
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)
                val source =
                    RutubeSourceAdapter(
                        http,
                        FakeMediaTool(),
                        { HttpToolConfig(perAttemptTimeout = 30.seconds, retryMaxAttempts = attempts) },
                    )

                // when
                source.loadMeta(MEDIA_ID)
                attempts = 2
                http.opened.clear()
                source.loadMeta(MEDIA_ID)

                // then
                http.opened.size shouldBe 2
            }

            "repeats an attempt that outlives the per-attempt budget" {
                runTest {
                    // given a source that is too slow on the first attempt and answers on the second
                    val http =
                        FakeHttpTool().route(
                            OPTIONS_URL,
                            HttpStub.Slow(5.seconds, recordedStub(RECORDING_OPTIONS)),
                            recordedStub(RECORDING_OPTIONS),
                        )
                    val settings = HttpToolConfig(perAttemptTimeout = 1.seconds)

                    // when
                    val actual = adapter(http, settings).loadMeta(MEDIA_ID)

                    // then
                    actual.shouldBeInstanceOf<LoadMetaResult.Found>()
                    http.opened.size shouldBe 2
                }
            }

            "gives up on an attempt that keeps outliving the per-attempt budget and answers transient" {
                runTest {
                    // given a source that never answers within the budget the configuration gives it
                    val http = FakeHttpTool().always(HttpStub.Slow(after = 5.seconds, answer = SERVER_ERROR_STUB))
                    val settings = HttpToolConfig(perAttemptTimeout = 1.seconds, retryMaxAttempts = 2)

                    // when
                    val actual = adapter(http, settings).loadMeta(MEDIA_ID)

                    // then a timed-out attempt is a hiccup, not the verdict of the source
                    actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
                    http.opened.size shouldBe 2
                }
            }

            "keeps a caller that gives up during an attempt cancelled" {
                runTest {
                    // given a source slow enough to still be in flight when the caller stops waiting
                    val http = FakeHttpTool().always(HttpStub.Slow(after = 5.seconds, answer = SERVER_ERROR_STUB))

                    // when
                    val attempt = async { adapter(http).loadMeta(MEDIA_ID) }
                    runCurrent()
                    attempt.cancel()
                    val thrown = runCatching { attempt.await() }.exceptionOrNull()

                    // then
                    thrown.shouldBeInstanceOf<CancellationException>()
                    http.opened.size shouldBe 1
                }
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

            "gives up on a segment whose attempts keep outliving the per-attempt budget" {
                runTest {
                    // given a segment that never answers within the budget its attempt is given
                    val slow = HttpStub.Slow(after = 5.seconds, answer = SERVER_ERROR_STUB)
                    val http = streaming().route(SEGMENT_2, slow)
                    val settings = HttpToolConfig(perAttemptTimeout = 1.seconds, retryMaxAttempts = 2)

                    // when
                    val actual = adapter(http, settings).download(MEDIA_ID, VIDEO_1080, clip("timeout")) {}

                    // then
                    actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
                    http.opened.count { it.url == SEGMENT_2 } shouldBe 2
                }
            }

            "breaks the retry wait as soon as the task is cancelled" {
                // given
                val http = FakeHttpTool().always(SERVER_ERROR_STUB)
                val policy = RetryPolicy.stopAtAttempts(5).constantDelay(50.milliseconds)

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
