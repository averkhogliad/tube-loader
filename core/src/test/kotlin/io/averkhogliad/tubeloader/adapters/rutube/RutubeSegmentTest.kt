package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.httpBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"
private const val OPTIONS_URL =
    "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val MASTER_URL = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
private const val VARIANT_1080 = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"
private const val SEGMENT_BASE = "https://segments.rutube.ru/0x1080.mp4/"

private val VIDEO_1080 = Quality("1080p", TrackKind.Video, "1080p")

private fun streaming() =
    FakeHttpTool()
        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf.m3u8")
        .routeRecording("${SEGMENT_BASE}segment-1-v1-a1.ts", "rutube/segment-1.ts")
        .routeRecording("${SEGMENT_BASE}segment-2-v1-a1.ts", "rutube/segment-2.ts")
        .routeRecording("${SEGMENT_BASE}segment-3-v1-a1.ts", "rutube/segment-3.ts")

private fun workDir() = Files.createTempDirectory("rutube-segments")

class RutubeSegmentTest : FreeSpec({

    "download" - {
        "reads every segment of the playlist, in order" {
            // given
            val http = streaming()
            val adapter = RutubeSourceAdapter(http)

            // when
            adapter.download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) {}

            // then
            http.opened.map { it.url }.filter { it.startsWith(SEGMENT_BASE) } shouldBe
                listOf(
                    "${SEGMENT_BASE}segment-1-v1-a1.ts",
                    "${SEGMENT_BASE}segment-2-v1-a1.ts",
                    "${SEGMENT_BASE}segment-3-v1-a1.ts",
                )
        }

        "reports a fraction of the downloaded segments after each of them" {
            // given
            val adapter = RutubeSourceAdapter(streaming())
            val progress = mutableListOf<SourceProgress>()

            // when
            adapter.download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) { progress += it }

            // then
            progress shouldBe
                listOf(
                    SourceProgress.Indeterminate,
                    SourceProgress.Indeterminate,
                    SourceProgress.Fraction(1.0 / 3),
                    SourceProgress.Fraction(2.0 / 3),
                    SourceProgress.Fraction(1.0),
                )
        }

        "returns Success once the segments are joined" {
            // given
            val adapter = RutubeSourceAdapter(streaming())

            // when
            val actual = adapter.download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) {}

            // then
            actual shouldBe DownloadResult.Success
        }

        "leaves no tmp file when a segment fails" {
            // given
            val adapter =
                RutubeSourceAdapter(
                    streaming().route("${SEGMENT_BASE}segment-2-v1-a1.ts", HttpStub.Fail(IOException("reset"))),
                )
            val dir = workDir()

            // when
            val actual = adapter.download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {}

            // then
            actual.shouldBeInstanceOf<DownloadResult.Failed>()
            Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
        }

        "returns NetworkTransient when a segment answers with a server error" {
            // given
            val adapter =
                RutubeSourceAdapter(
                    streaming()
                        .route(
                            "${SEGMENT_BASE}segment-2-v1-a1.ts",
                            HttpStub.Respond(httpBody("boom".toByteArray(), status = 503)),
                        ),
                )

            // when
            val actual = adapter.download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) {}

            // then
            actual shouldBe DownloadResult.Failed(DownloadError.NetworkTransient)
        }

        "leaves no tmp file behind when the transfer is cancelled" {
            // given
            val adapter = RutubeSourceAdapter(streaming())
            val dir = workDir()

            // when
            val thrown =
                runCatching {
                    adapter.download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {
                        throw CancellationException("cancelled by the test")
                    }
                }.exceptionOrNull()

            // then
            thrown.shouldBeInstanceOf<CancellationException>()
            Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
        }
    }
})
