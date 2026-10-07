package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.seconds

private const val LIVE_MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"

private const val OPTIONS_MATCH = "https://rutube.ru/api/play/options/$LIVE_MEDIA_ID/"
private const val MASTER_MATCH = "https://bl.rutube.ru/route/$LIVE_MEDIA_ID.m3u8"

/** The variant the selection rule picks out of the recorded master, and its own location. */
private const val VARIANT_MATCH =
    "https://river-m9-mskix-d3100.rtbcdn.ru/hls-vod/bK-lXdzt0PyCVH-INsCslA/1791896394/3500/" +
        "0x5000c500e9cf2cc2/89f01c7070574acb92ca8adda1274dfa.mp4.m3u8?i=1920x1080_2203"

private val LIVE_VIDEO_1080 = Quality("1080p", TrackKind.Video, "1080p")

private val SEGMENT_BYTES = ByteArray(64) { it.toByte() }

private val EXPECTED_SEGMENTS =
    (1..3).map { segment ->
        "https://river-m9-mskix-d3100.rtbcdn.ru/hls-vod/bK-lXdzt0PyCVH-INsCslA/1791896394/3500/" +
            "0x5000c500e9cf2cc2/89f01c7070574acb92ca8adda1274dfa.mp4/segment-$segment-v1-a1.ts"
    }

/**
 * Drives the adapter through playlists captured from the source itself, so the shape of a real leaf
 * playlist is covered: its segment names are relative and its ladder is long. The recording holds the
 * first three segments — the rest of the ladder is never fetched.
 */
@Tag("integration")
@Tags("integration")
class RutubeSourceAdapterIT :
    FreeSpec({

        "download" - {
            "resolves the relative segment names of a recorded leaf playlist against its own location" {
                // given
                val http = recorded()
                val adapter =
                    RutubeSourceAdapter(
                        http,
                        FakeMediaTool().copyStreams(),
                        { HttpToolConfig(perAttemptTimeout = 30.seconds) },
                    )
                val dir = Files.createTempDirectory("rutube-it")
                val target = dir.resolve("clip.mp4")

                // when
                val actual = adapter.download(LIVE_MEDIA_ID, LIVE_VIDEO_1080, target) {}

                // then
                actual shouldBe DownloadResult.Success
                http.opened.map { it.url }.filter { it.endsWith(".ts") } shouldBe EXPECTED_SEGMENTS
            }

            "walks from the metadata request down to the segments of the recorded master" {
                // given
                val http = recorded()
                val adapter =
                    RutubeSourceAdapter(
                        http,
                        FakeMediaTool().copyStreams(),
                        { HttpToolConfig(perAttemptTimeout = 30.seconds) },
                    )
                val dir = Files.createTempDirectory("rutube-it")
                val target = dir.resolve("clip.mp4")

                // when
                adapter.download(LIVE_MEDIA_ID, LIVE_VIDEO_1080, target) {}

                // then
                val requested = http.opened.map { it.url }
                requested.first() shouldBe
                    "$OPTIONS_MATCH?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
                requested[1].startsWith(MASTER_MATCH) shouldBe true
                requested[2] shouldBe VARIANT_MATCH
            }

            "ends on an absolute update and leaves no staging file behind" {
                // given
                val adapter =
                    RutubeSourceAdapter(
                        recorded(),
                        FakeMediaTool().copyStreams(),
                        { HttpToolConfig(perAttemptTimeout = 30.seconds) },
                    )
                val dir = Files.createTempDirectory("rutube-it")
                val target = dir.resolve("clip.mp4")
                val progress = mutableListOf<SourceProgress>()

                // when
                adapter.download(LIVE_MEDIA_ID, LIVE_VIDEO_1080, target) { progress += it }

                // then
                val size = target.readBytes().size.toLong()
                progress.last() shouldBe SourceProgress.Absolute(size, size)
                target.exists() shouldBe true
                dir.resolve("clip.mp4.tmp").exists() shouldBe false
            }
        }
    })

/**
 * Answers the recorded playlists; a request for a recorded segment gets its bytes, anything else
 * fails, so a gap in the recording cannot pass as an outcome of the adapter under test.
 */
private fun recorded(): FakeHttpTool {
    val http =
        FakeHttpTool()
            .routeRecording(OPTIONS_MATCH, "rutube/recorded/playOptions.json")
            .routeRecording(MASTER_MATCH, "rutube/recorded/m3u8-master.m3u8")
            .routeRecording(VARIANT_MATCH, "rutube/recorded/m3u8-leaf-1080-head.m3u8")
    EXPECTED_SEGMENTS.forEach { http.route(url = it, content = SEGMENT_BYTES) }
    return http
}
