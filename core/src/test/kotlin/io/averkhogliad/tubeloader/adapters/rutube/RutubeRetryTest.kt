package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.httpBody
import io.averkhogliad.tubeloader.core.port.textBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeout

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"
private const val OPTIONS_URL =
    "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val MASTER_URL = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
private const val VARIANT_1080 = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"
private const val SEGMENT_BASE = "https://segments.rutube.ru/0x1080.mp4/"
private const val SEGMENT_2 = "${SEGMENT_BASE}segment-2-v1-a1.ts"

private val VIDEO_1080 = Quality("1080p", TrackKind.Video, "1080p")

private fun optionsRecording(status: Int = 200): HttpStub =
    HttpStub.Respond(textBody(FakeHttpTool.resourceText("rutube/playOptions-download.json"), status))

private fun serverError(): HttpStub = HttpStub.Respond(httpBody("unavailable".toByteArray(), status = 503))

private fun clientError(): HttpStub = HttpStub.Respond(httpBody("gone".toByteArray(), status = 404))

private fun streaming() =
    FakeHttpTool()
        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf.m3u8")
        .routeRecording("${SEGMENT_BASE}segment-1-v1-a1.ts", "rutube/segment-1.ts")
        .routeRecording(SEGMENT_2, "rutube/segment-2.ts")
        .routeRecording("${SEGMENT_BASE}segment-3-v1-a1.ts", "rutube/segment-3.ts")

private fun adapter(http: FakeHttpTool) = RutubeSourceAdapter(http, FakeMediaTool().copyStreams())

private fun workDir() = Files.createTempDirectory("rutube-retry")

class RutubeRetryTest : FreeSpec({

    "loadMeta" - {
        "asks again after a server error and answers from the next response" {
            // given
            val http = FakeHttpTool().route(OPTIONS_URL, serverError(), serverError(), optionsRecording())

            // when
            val actual = adapter(http).loadMeta(MEDIA_ID)

            // then
            actual.shouldBeInstanceOf<LoadMetaResult.Found>()
            http.opened.size shouldBe 3
        }

        "gives up after five attempts and reports a transient failure" {
            // given
            val http = FakeHttpTool().always(serverError())

            // when
            val actual = adapter(http).loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
            http.opened.size shouldBe 5
        }

        "retries a transport failure" {
            // given
            val http = FakeHttpTool().route(OPTIONS_URL, HttpStub.Fail(UnknownHostException("rutube.ru")), optionsRecording())

            // when
            val actual = adapter(http).loadMeta(MEDIA_ID)

            // then
            actual.shouldBeInstanceOf<LoadMetaResult.Found>()
            http.opened.size shouldBe 2
        }

        "does not retry a client error" {
            // given
            val http = FakeHttpTool().route(OPTIONS_URL, clientError(), optionsRecording())

            // when
            val actual = adapter(http).loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            http.opened.size shouldBe 1
        }
    }

    "download" - {
        "recovers when a segment fails once" {
            // given
            val http = streaming().route(SEGMENT_2, HttpStub.Fail(IOException("reset")), HttpStub.Respond(textBody("SEGMENT-TWO-BYTES")))

            // when
            val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) {}

            // then
            actual shouldBe DownloadResult.Success
        }

        "gives up on a segment that stays unavailable and reports a transient failure" {
            // given
            val http = streaming().route(SEGMENT_2, serverError())
            val dir = workDir()

            // when
            val actual = adapter(http).download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {}

            // then
            actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
            Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
        }

        "breaks the retry wait as soon as the task is cancelled" {
            // given
            val http = FakeHttpTool().always(serverError())

            // when
            val thrown = runCatching { withTimeout(300.milliseconds) { adapter(http).loadMeta(MEDIA_ID) } }.exceptionOrNull()

            // then
            thrown.shouldBeInstanceOf<CancellationException>()
            http.opened.size shouldBe 2
        }
    }
})
