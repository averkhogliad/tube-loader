package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.readBytes

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"
private const val OPTIONS_URL =
    "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
private const val MASTER_URL = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
private const val VARIANT_1080 = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"
private const val SEGMENT_BASE = "https://segments.rutube.ru/0x1080.mp4/"

private val VIDEO_1080 = Quality("1080p", TrackKind.Video, "1080p")

private val JOINED_SEGMENTS =
    listOf("rutube/segment-1.ts", "rutube/segment-2.ts", "rutube/segment-3.ts")
        .map(FakeHttpTool::resourceText)
        .joinToString("")
        .toByteArray()

private fun streaming() =
    FakeHttpTool()
        .routeRecording(OPTIONS_URL, "rutube/playOptions-download.json")
        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf.m3u8")
        .routeRecording("${SEGMENT_BASE}segment-1-v1-a1.ts", "rutube/segment-1.ts")
        .routeRecording("${SEGMENT_BASE}segment-2-v1-a1.ts", "rutube/segment-2.ts")
        .routeRecording("${SEGMENT_BASE}segment-3-v1-a1.ts", "rutube/segment-3.ts")

private fun workDir() = Files.createTempDirectory("rutube-finalize")

class RutubeFinalizeTest : FreeSpec({

    "download" - {
        "hands the joined file over to remux and the result to the target path" {
            // given
            val media = FakeMediaTool().copyStreams()
            val adapter = RutubeSourceAdapter(streaming(), media)
            val dir = workDir()
            val target = dir.resolve("clip.mp4")

            // when
            val actual = adapter.download(MEDIA_ID, VIDEO_1080, target) {}

            // then
            actual shouldBe DownloadResult.Success
            val call = media.remuxCalls.single()
            call.input shouldBe dir.resolve("clip.mp4.tmp")
            call.output shouldBe target
        }

        "leaves the remuxed file at the target and no tmp behind" {
            // given
            val adapter = RutubeSourceAdapter(streaming(), FakeMediaTool().copyStreams())
            val dir = workDir()
            val target = dir.resolve("clip.mp4")

            // when
            adapter.download(MEDIA_ID, VIDEO_1080, target) {}

            // then
            target.readBytes() shouldBe JOINED_SEGMENTS
            Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
        }

        "closes the progress with the size of the file it produced" {
            // given
            val adapter = RutubeSourceAdapter(streaming(), FakeMediaTool().copyStreams())
            val target = workDir().resolve("clip.mp4")
            val progress = mutableListOf<SourceProgress>()

            // when
            adapter.download(MEDIA_ID, VIDEO_1080, target) { progress += it }

            // then
            progress.last() shouldBe SourceProgress.Absolute(target.readBytes().size.toLong(), target.readBytes().size.toLong())
        }

        "reports an indeterminate stage while the container is rewritten" {
            // given
            val adapter = RutubeSourceAdapter(streaming(), FakeMediaTool().copyStreams())
            val progress = mutableListOf<SourceProgress>()

            // when
            adapter.download(MEDIA_ID, VIDEO_1080, workDir().resolve("clip.mp4")) { progress += it }

            // then
            progress.takeLast(2).first() shouldBe SourceProgress.Indeterminate
        }

        "returns ExtractorBroken when the container rewrite fails" {
            // given
            val media = FakeMediaTool()
            media.onRemux = { _, _ -> Result.failure(IllegalStateException("ffmpeg is gone")) }
            val adapter = RutubeSourceAdapter(streaming(), media)
            val dir = workDir()

            // when
            val actual = adapter.download(MEDIA_ID, VIDEO_1080, dir.resolve("clip.mp4")) {}

            // then
            actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
        }
    }
})
