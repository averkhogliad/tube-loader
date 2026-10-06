package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.contract.DownloadCase
import io.averkhogliad.tubeloader.core.contract.FindCase
import io.averkhogliad.tubeloader.core.contract.MetaCase
import io.averkhogliad.tubeloader.core.contract.RecordedResponses
import io.averkhogliad.tubeloader.core.contract.SourceAdapterFixtures
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import kotlin.time.Duration.Companion.milliseconds

const val RECORDED_MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"

private const val MARKERLESS_MEDIA_ID = "11111111111111111111111111111111"
private const val RECORDED_MASTER = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
private const val MARKERLESS_MASTER = "https://bl.rutube.ru/route/no-quality.m3u8?sign=s"
private const val RECORDED_VARIANT = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"
private const val SEGMENT_DIR = "https://segments.rutube.ru/0x1080.mp4"

val RECORDED_VIDEO_1080 = Quality("1080p", TrackKind.Video, "1080p")

private fun optionsUrl(id: String) =
    "https://rutube.ru/api/play/options/$id/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"

/**
 * The golden recording of the source, wired into the seam the contract suite drives. Every URL the
 * recording knows is routed to its file; an unrouted one fails, so a gap in the recording cannot pass
 * as an outcome of the adapter under test.
 */
fun rutubeRecordedHttp(): FakeHttpTool =
    FakeHttpTool()
        .always(HttpStub.Fail(IllegalArgumentException("the recording knows no such url")))
        .routeRecording(optionsUrl(RECORDED_MEDIA_ID), "rutube/playOptions-download.json")
        .routeRecording(optionsUrl(MARKERLESS_MEDIA_ID), "rutube/playOptions-no-quality.json")
        .routeRecording(RECORDED_MASTER, "rutube/m3u8-master.m3u8")
        .routeRecording(MARKERLESS_MASTER, "rutube/m3u8-master-no-quality.m3u8")
        .routeRecording(RECORDED_VARIANT, "rutube/m3u8-leaf.m3u8")
        .routeRecording("$SEGMENT_DIR/segment-1-v1-a1.ts", "rutube/segment-1.ts")
        .routeRecording("$SEGMENT_DIR/segment-2-v1-a1.ts", "rutube/segment-2.ts")
        .routeRecording("$SEGMENT_DIR/segment-3-v1-a1.ts", "rutube/segment-3.ts")

fun rutubeAdapterFixtures(): SourceAdapterFixtures =
    SourceAdapterFixtures(
        // the recording is served through the seam above, so the suite needs no second copy of it
        responses = RecordedResponses.None,
        find = rutubeFindCases(),
        meta = rutubeMetaCases(),
        download = rutubeDownloadCases(),
    )

private fun rutubeFindCases() =
    listOf(
        FindCase(
            name = "returns the id for a canonical video url",
            input = "https://rutube.ru/video/$RECORDED_MEDIA_ID/",
            expected = FindResult.Found(RECORDED_MEDIA_ID),
        ),
        FindCase(
            name = "returns the id for an embed url",
            input = "https://rutube.ru/play/embed/$RECORDED_MEDIA_ID",
            expected = FindResult.Found(RECORDED_MEDIA_ID),
        ),
        FindCase(
            name = "returns Unsupported for a url of another host",
            input = "https://vimeo.com/video/$RECORDED_MEDIA_ID/",
            expected = FindResult.Unsupported,
        ),
        FindCase(
            name = "returns Unsupported for an id of the wrong shape",
            input = "https://rutube.ru/video/not-an-id/",
            expected = FindResult.Unsupported,
        ),
    )

private fun rutubeMetaCases() =
    listOf(
        MetaCase(
            name = "parses the recorded meta into MediaMeta and qualities",
            id = RECORDED_MEDIA_ID,
            expected = LoadMetaResult.Found(RECORDED_META),
        ),
    )

private val RECORDED_META =
    MediaMeta(
        id = RECORDED_MEDIA_ID,
        title =
            "Почему хороший продукт больше не преимущество: конкуренция, " +
                "копирование и экономика IT-бизнеса #93",
        author = "Организованное программирование",
        duration = 5589952.milliseconds,
        thumbnailUrl =
            "https://pic.rtbcdn.ru/video/2026-09-20/c8/dc/" +
                "c8dc7f1b3bfb649f3a0f86c1e2da5460.jpg",
        qualities = listOf(RECORDED_VIDEO_1080),
    )

private fun rutubeDownloadCases() =
    listOf(
        DownloadCase(
            name = "writes the recorded stream of the chosen quality",
            id = RECORDED_MEDIA_ID,
            quality = RECORDED_VIDEO_1080,
            expected = DownloadResult.Success,
            expectedContent = recordedStream(),
        ),
        DownloadCase(
            name = "reports a broken extractor when the master names no quality",
            id = MARKERLESS_MEDIA_ID,
            quality = RECORDED_VIDEO_1080,
            expected = DownloadResult.Failed(DownloadError.ExtractorBroken),
        ),
    )

private fun recordedStream(): ByteArray =
    listOf("rutube/segment-1.ts", "rutube/segment-2.ts", "rutube/segment-3.ts")
        .flatMap { FakeHttpTool.resourceText(it).toByteArray().toList() }
        .toByteArray()
