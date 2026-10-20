package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.contract.DownloadCase
import io.averkhogliad.tubeloader.core.contract.FindCase
import io.averkhogliad.tubeloader.core.contract.MetaCase
import io.averkhogliad.tubeloader.core.contract.RecordedResponses
import io.averkhogliad.tubeloader.core.contract.SourceAdapterFixtures
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.lang.Process
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val RESOLVE_FLAG = "-J"
private const val BINARY = "yt-dlp"
private const val WATCH_URL = "https://www.youtube.com/watch?v="
private const val PRODUCED_FILE = "video.mp4"
private const val HOMEDIR = "ffmpeg"
private const val FAILURE_CODE = 1
private const val GRACE = 100L

private const val RECORDED = "dQw4w9WgXcQ"
private const val SPARSE = "sparse-1"
private const val GONE = "gone-1"
private const val EXPIRED = "expired-1"
private const val UNREACHABLE = "network-1"
private const val ANTIBOT = "antibot-1"
private const val BROKEN = "broken-1"

private val RECORDED_QUALITY = Quality("720p", TrackKind.Video, "720p")

private val RECORDED_META =
    MediaMeta(
        id = RECORDED,
        title = "Big Buck Bunny",
        author = "Blender Foundation",
        duration = 596470.milliseconds,
        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        qualities =
            listOf(
                Quality("1080p", TrackKind.Video, "1080p"),
                Quality("720p", TrackKind.Video, "720p"),
                Quality("360p", TrackKind.Video, "360p"),
            ),
    )

private val SPARSE_META =
    MediaMeta(
        id = SPARSE,
        title = "Sparse clip",
        author = "",
        duration = Duration.ZERO,
        thumbnailUrl = null,
        qualities = listOf(Quality("360p", TrackKind.Video, "360p")),
    )

private val FIND_CASES: List<FindCase> =
    listOf(
        FindCase(
            name = "reads the id of a watch url",
            input = "$WATCH_URL$RECORDED",
            expected = FindResult.Found(RECORDED),
        ),
        FindCase(
            name = "reads the id of a short url",
            input = "https://youtu.be/$RECORDED",
            expected = FindResult.Found(RECORDED),
        ),
        FindCase(
            name = "reads the id of a shorts url",
            input = "https://www.youtube.com/shorts/$RECORDED",
            expected = FindResult.Found(RECORDED),
        ),
        FindCase(
            name = "reads the id of an embed url",
            input = "https://www.youtube.com/embed/$RECORDED",
            expected = FindResult.Found(RECORDED),
        ),
        FindCase(
            name = "leaves the url of another source alone",
            input = "https://vimeo.com/$RECORDED",
            expected = FindResult.Unsupported,
        ),
        FindCase(
            name = "names a watch url without an id as missing",
            input = WATCH_URL,
            expected = FindResult.NotFound,
        ),
    )

private val META_CASES: List<MetaCase> =
    listOf(
        MetaCase(
            name = "resolves the recording into the reference meta",
            id = RECORDED,
            expected = LoadMetaResult.Found(RECORDED_META),
        ),
        MetaCase(
            name = "tolerates the optional fields of a sparse recording",
            id = SPARSE,
            expected = LoadMetaResult.Found(SPARSE_META),
        ),
        failedMeta("names a media the source no longer serves", GONE, DownloadError.NotFound),
        failedMeta("names a stale address", EXPIRED, DownloadError.UrlExpired),
        failedMeta("names a source that was not reached", UNREACHABLE, DownloadError.NetworkTransient),
        failedMeta("names the bot check", ANTIBOT, DownloadError.AntiBotBlocked),
        failedMeta("names a translation of its own as broken", BROKEN, DownloadError.ExtractorBroken),
    )

private fun failedMeta(name: String, id: String, error: DownloadError): MetaCase =
    MetaCase(
        name = name,
        id = id,
        expected = LoadMetaResult.Failed(error),
    )

private fun downloadCases(content: ByteArray): List<DownloadCase> =
    listOf(
        DownloadCase(
            name = "writes the recorded stream into the part path",
            id = RECORDED,
            quality = RECORDED_QUALITY,
            expected = DownloadResult.Success,
            expectedContent = content,
        ),
        failedTransfer("names a media the source no longer serves", GONE, DownloadError.NotFound),
        failedTransfer("names a stale address", EXPIRED, DownloadError.UrlExpired),
        failedTransfer("names a source that was not reached", UNREACHABLE, DownloadError.NetworkTransient),
        failedTransfer("names the bot check", ANTIBOT, DownloadError.AntiBotBlocked),
        failedTransfer("names a broken transfer", BROKEN, DownloadError.ExtractorBroken),
    )

private fun failedTransfer(name: String, id: String, error: DownloadError): DownloadCase =
    DownloadCase(
        name = name,
        id = id,
        quality = RECORDED_QUALITY,
        expected = DownloadResult.Failed(error),
    )

/** What the recorded tool answers about one media: the resolve payload, the transfer lines, or the failure. */
private data class YoutubeTake(
    val payload: String = "",
    val progress: List<String> = emptyList(),
    val content: ByteArray = ByteArray(0),
    val failure: String? = null,
)

/** The recording of the tool, keyed by media id: an id that was never recorded fails the fixture. */
private class YoutubeRecording(private val takes: Map<String, YoutubeTake>) {

    fun takeOf(mediaId: String): YoutubeTake =
        takes[mediaId] ?: throw IllegalArgumentException("no recorded answer for $mediaId")
}

/** The golden run of the contract: the cases it asserts on, and the adapter driven by the recording. */
internal class YoutubeFixtures(
    val cases: SourceAdapterFixtures,
    val adapter: () -> SourceAdapter,
    private val roots: List<Path>,
) {

    suspend fun cleanup() {
        roots.forEach { deleteWithRetry(it) }
    }
}

internal fun youtubeFixtures(): YoutubeFixtures {
    val content = goldenBytes("download.bin")
    val recording =
        YoutubeRecording(
            mapOf(
                RECORDED to
                    YoutubeTake(
                        payload = goldenText("meta.json"),
                        progress = goldenLines("download-progress.txt"),
                        content = content,
                    ),
                SPARSE to YoutubeTake(payload = goldenText("meta-sparse.json")),
                GONE to YoutubeTake(failure = goldenText("error-unavailable.txt")),
                EXPIRED to YoutubeTake(failure = goldenText("error-forbidden.txt")),
                UNREACHABLE to YoutubeTake(failure = goldenText("error-network.txt")),
                ANTIBOT to YoutubeTake(failure = goldenText("error-antibot.txt")),
                BROKEN to YoutubeTake(failure = goldenText("error-format.txt")),
            ),
        )
    val world = YoutubeWorld(recording)
    return YoutubeFixtures(
        cases = SourceAdapterFixtures(RecordedResponses.None, FIND_CASES, META_CASES, downloadCases(content)),
        adapter = world::adapter,
        roots = listOf(world.root),
    )
}

/**
 * A home directory of its own and a tool of its own: the answers come from the recording, and the file
 * of a finished transfer is part of it, so nothing of the tool lands where the build runs from.
 */
private class YoutubeWorld(private val recording: YoutubeRecording) {

    internal val root: Path = Files.createTempDirectory("youtube-contract")

    private val settings =
        YtDlpConfig(
            paths = ToolPaths.of(root.resolve(HOMEDIR), "Linux"),
            binaryPath = Path.of(BINARY),
        )

    fun adapter(): SourceAdapter = adapterOver(settings, ::processOf)

    private fun processOf(launch: YtDlpLaunch): Process =
        if (RESOLVE_FLAG in launch.command) {
            answering(recording.takeOf(idOfUrl(launch.command.last()))) { take -> take.payload }
        } else {
            answering(recording.takeOf(idOfUrl(launch.command.last())), ::producedOutputOf)
        }

    private fun answering(take: YoutubeTake, output: (YoutubeTake) -> String): Process =
        take.failure
            ?.let { failure -> toolProcess(ToolReply(exitedWith = FAILURE_CODE, error = failure)) }
            ?: toolProcess(ToolReply(output = output(take)))

    /** The bytes of the recording land where the tool would leave them, and its path is named on the output. */
    private fun producedOutputOf(take: YoutubeTake): String {
        val produced = Files.createTempDirectory(root, "produced-").resolve(PRODUCED_FILE)
        Files.write(produced, take.content)
        val filepath = "$FILEPATH_MARK$produced"
        return (take.progress + filepath).joinToString("\n")
    }
}

private fun idOfUrl(url: String): String = url.substringAfterLast('=')

// --- Stubs and golden loaders ---

internal val YOUTUBE_SETTINGS =
    YtDlpConfig(paths = ToolPaths.of(Path.of("home"), "Linux"), binaryPath = Path.of(BINARY))

private object GoldenFixtures

internal fun goldenText(name: String): String {
    val path = "golden/youtube/$name"
    val stream =
        requireNotNull(GoldenFixtures::class.java.classLoader?.getResourceAsStream(path)) {
            "missing golden fixture: $path"
        }
    return stream.use { it.readBytes() }.decodeToString()
}

internal fun goldenBytes(name: String): ByteArray = goldenText(name).toByteArray()

internal fun goldenLines(name: String): List<String> = goldenText(name).lines().filter(String::isNotBlank)

/**
 * What the tool of a test answers: [exits] false means it never leaves on its own, which is what a
 * call faces when its budget runs out.
 */
internal data class ToolReply(
    val exitedWith: Int = 0,
    val output: String = "",
    val error: String = "",
    val exits: Boolean = true,
)

/** The adapter over a tool of its own: every start is recorded in [launches] and the process answers [reply]. */
internal fun youtubeAdapter(
    settings: YtDlpConfig = YOUTUBE_SETTINGS,
    launches: MutableList<YtDlpLaunch> = mutableListOf(),
    reply: ToolReply = ToolReply(),
): YoutubeSourceAdapter =
    adapterOver(settings) { launch ->
        launches += launch
        toolProcess(reply)
    }

/** The adapter over a tool of its own: [start] answers every call with a process of that call. */
internal fun adapterOver(settings: YtDlpConfig, start: (YtDlpLaunch) -> Process): YoutubeSourceAdapter =
    YoutubeSourceAdapter(
        runner =
            YtDlpProcessRunner(
                workingDirectory = Path.of("work"),
                graceTimeoutMillis = GRACE,
                start = start,
            ),
        config = { settings },
    )

internal fun toolProcess(reply: ToolReply): Process {
    val process = mockk<Process>(relaxed = true)
    val finished = mutableListOf<CompletableFuture<Process>>()
    var killed = false
    every { process.inputStream } returns ByteArrayInputStream(reply.output.toByteArray())
    every { process.errorStream } returns ByteArrayInputStream(reply.error.toByteArray())
    every { process.onExit() } answers {
        CompletableFuture<Process>().also { future ->
            if (reply.exits || killed) future.complete(process)
            finished += future
        }
    }
    every { process.exitValue() } returns reply.exitedWith
    every { process.destroy() } answers {
        killed = true
        finished.forEach { future -> future.complete(process) }
    }
    return process
}
