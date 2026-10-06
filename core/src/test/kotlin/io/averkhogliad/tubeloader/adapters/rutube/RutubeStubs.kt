package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.averkhogliad.tubeloader.core.port.HttpBody
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.textBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal const val RECORDING_OPTIONS = "rutube/playOptions-download.json"

internal const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"

internal const val MASTER_URL = "https://bl.rutube.ru/route/0xmaster.m3u8?sign=s"
internal const val VARIANT_1080 = "https://river-1.rutube.ru/hls-vod/1080/0x1080.mp4.m3u8?i=1920x1080_2203"
internal const val SEGMENT_BASE = "https://segments.rutube.ru/0x1080.mp4/"
internal const val SEGMENT_1 = "${SEGMENT_BASE}segment-1-v1-a1.ts"
internal const val SEGMENT_2 = "${SEGMENT_BASE}segment-2-v1-a1.ts"
internal const val SEGMENT_3 = "${SEGMENT_BASE}segment-3-v1-a1.ts"

internal val VIDEO_1080 = Quality(QUALITY_1080, TrackKind.Video, QUALITY_1080)

internal val OPTIONS_URL = expectedOptionsUrl(MEDIA_ID)

internal fun expectedOptionsUrl(id: String) =
    "https://rutube.ru/api/play/options/$id/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"

/**
 * The whole happy path of the source: the metadata request, both playlists and every segment.
 */
internal fun streaming(): FakeHttpTool =
    FakeHttpTool()
        .routeRecording(OPTIONS_URL, RECORDING_OPTIONS)
        .routeRecording(MASTER_URL, "rutube/m3u8-master.m3u8")
        .routeRecording(VARIANT_1080, "rutube/m3u8-leaf.m3u8")
        .routeRecording(SEGMENT_1, "rutube/segment-1.ts")
        .routeRecording(SEGMENT_2, "rutube/segment-2.ts")
        .routeRecording(SEGMENT_3, "rutube/segment-3.ts")

internal fun adapter(http: FakeHttpTool) = RutubeSourceAdapter(http, FakeMediaTool().copyStreams())

internal fun workDir(prefix: String): Path = Files.createTempDirectory(prefix)

/**
 * A target path inside a fresh temporary directory.
 */
internal fun clip(prefix: String): Path = workDir(prefix).resolve("clip.mp4")

/**
 * A response recorded from the source, served with the given status.
 */
internal fun recordedStub(resource: String, status: Int = 200): HttpStub =
    HttpStub.Respond(textBody(FakeHttpTool.resourceText(resource), status))

internal fun recordedStubWith(text: String, status: Int): HttpStub = HttpStub.Respond(textBody(text, status))

/**
 * A response whose stream reports whether it was closed, so that a body the retry loop discards can
 * be observed.
 */
internal class TrackedBody(val response: HttpStub, private val closed: AtomicBoolean) {
    val isClosed: Boolean get() = closed.get()
}

internal fun trackedBody(content: String, status: Int): TrackedBody {
    val closed = AtomicBoolean()
    val stream =
        object : ByteArrayInputStream(content.toByteArray()) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }
    return TrackedBody(HttpStub.Respond(HttpBody(status, stream)), closed)
}

internal val SERVER_ERROR_STUB: HttpStub = HttpStub.Respond(textBody("unavailable", status = 503))

internal val CLIENT_ERROR_STUB: HttpStub = HttpStub.Respond(textBody("gone", status = 404))

internal val TRANSPORT_FAILURE_STUB: HttpStub = HttpStub.Fail(UnknownHostException("rutube.ru"))

internal val CONNECTION_RESET_STUB: HttpStub = HttpStub.Fail(IOException("reset"))
