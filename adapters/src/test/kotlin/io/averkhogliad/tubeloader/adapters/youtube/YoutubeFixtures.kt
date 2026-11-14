package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.contract.DownloadCase
import io.averkhogliad.tubeloader.core.contract.MetaCase
import io.averkhogliad.tubeloader.core.contract.RecordedResponses
import io.averkhogliad.tubeloader.core.contract.ResolveIdCase
import io.averkhogliad.tubeloader.core.contract.SourceAdapterFixtures
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.infra.binaries.ManagedBinary
import io.averkhogliad.tubeloader.infra.binaries.ProcessLaunch
import io.averkhogliad.tubeloader.infra.binaries.ProcessOutcome
import io.averkhogliad.tubeloader.infra.binaries.ProcessRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val RESOLVE_FLAG = "-J"
private const val BINARY = "yt-dlp"
private const val WATCH_URL = "https://www.youtube.com/watch?v="
private const val PRODUCED_FILE = "video.mp4"
private const val HOMEDIR = "ffmpeg"
private const val FAILURE_CODE = 1

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

private val RESOLVE_ID_CASES: List<ResolveIdCase> =
    listOf(
        ResolveIdCase(
            name = "reads the id of a watch url",
            input = "$WATCH_URL$RECORDED",
            expected = ResolveIdResult.Found(RECORDED),
        ),
        ResolveIdCase(
            name = "reads the id of a short url",
            input = "https://youtu.be/$RECORDED",
            expected = ResolveIdResult.Found(RECORDED),
        ),
        ResolveIdCase(
            name = "reads the id of a shorts url",
            input = "https://www.youtube.com/shorts/$RECORDED",
            expected = ResolveIdResult.Found(RECORDED),
        ),
        ResolveIdCase(
            name = "reads the id of an embed url",
            input = "https://www.youtube.com/embed/$RECORDED",
            expected = ResolveIdResult.Found(RECORDED),
        ),
        ResolveIdCase(
            name = "leaves the url of another source alone",
            input = "https://vimeo.com/$RECORDED",
            expected = ResolveIdResult.Unsupported,
        ),
        ResolveIdCase(
            name = "names a watch url without an id as missing",
            input = WATCH_URL,
            expected = ResolveIdResult.NotFound,
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
        failedMeta(
            "names a media the source no longer serves",
            GONE,
            DownloadError.NotFound,
            "error-unavailable.txt",
        ),
        failedMeta(
            "names a stale address",
            EXPIRED,
            DownloadError.UrlExpired,
            "error-forbidden.txt",
        ),
        failedMeta(
            "names a source that was not reached",
            UNREACHABLE,
            DownloadError.NetworkTransient,
            "error-network.txt",
        ),
        failedMeta(
            "names the bot check",
            ANTIBOT,
            DownloadError.AntiBotBlocked,
            "error-antibot.txt",
        ),
        failedMeta(
            "names a translation of its own as broken",
            BROKEN,
            DownloadError.ExtractorBroken,
            "error-format.txt",
        ),
    )

private fun failedMeta(name: String, id: String, error: DownloadError, fixture: String): MetaCase =
    MetaCase(
        name = name,
        id = id,
        expected = LoadMetaResult.Failed(error, IOException(goldenText(fixture).trim())),
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
        failedTransfer(
            "names a media the source no longer serves",
            GONE,
            DownloadError.NotFound,
            "error-unavailable.txt",
        ),
        failedTransfer(
            "names a stale address",
            EXPIRED,
            DownloadError.UrlExpired,
            "error-forbidden.txt",
        ),
        failedTransfer(
            "names a source that was not reached",
            UNREACHABLE,
            DownloadError.NetworkTransient,
            "error-network.txt",
        ),
        failedTransfer(
            "names the bot check",
            ANTIBOT,
            DownloadError.AntiBotBlocked,
            "error-antibot.txt",
        ),
        failedTransfer(
            "names a broken transfer",
            BROKEN,
            DownloadError.ExtractorBroken,
            "error-format.txt",
        ),
    )

private fun failedTransfer(name: String, id: String, error: DownloadError, fixture: String): DownloadCase =
    DownloadCase(
        name = name,
        id = id,
        quality = RECORDED_QUALITY,
        expected = DownloadResult.Failed(error, IOException(goldenText(fixture).trim())),
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
        cases = SourceAdapterFixtures(RecordedResponses.None, RESOLVE_ID_CASES, META_CASES, downloadCases(content)),
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

    fun adapter(): SourceAdapter = adapterOver(settings, ::replyOf)

    private fun replyOf(launch: ProcessLaunch): ToolReply =
        if (RESOLVE_FLAG in launch.command) {
            answering(recording.takeOf(idOfUrl(launch.command.last()))) { take -> take.payload }
        } else {
            answering(recording.takeOf(idOfUrl(launch.command.last())), ::producedOutputOf)
        }

    private fun answering(take: YoutubeTake, output: (YoutubeTake) -> String): ToolReply =
        take.failure
            ?.let { failure -> ToolReply(exitedWith = FAILURE_CODE, error = failure) }
            ?: ToolReply(output = output(take))

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

/** The adapter over a tool of its own: every start is recorded in [launches] and the runner answers [reply]. */
internal fun youtubeAdapter(
    settings: YtDlpConfig = YOUTUBE_SETTINGS,
    launches: MutableList<ProcessLaunch> = mutableListOf(),
    reply: ToolReply = ToolReply(),
): YoutubeSourceAdapter =
    adapterOver(settings) { launch ->
        launches += launch
        reply
    }

/** The adapter over a tool of its own: [replyOf] answers every call with what the tool would say. */
internal fun adapterOver(settings: YtDlpConfig, replyOf: (ProcessLaunch) -> ToolReply): YoutubeSourceAdapter =
    YoutubeSourceAdapter(
        runner = YtDlpProcessRunner(FakeToolRunner(replyOf), Path.of("work")),
        config = { settings },
        // M1 publishes the Windows build only: the policy is pinned so the host decides nothing here
        distribution = ManagedBinary(settings.paths.ytDlpBinary, { ytDlpPolicy("Windows 11") }),
    )

/** The adapter over a process mechanism that answers every call with [outcome]. */
internal fun adapterWithOutcome(
    settings: YtDlpConfig = YOUTUBE_SETTINGS,
    outcome: ProcessOutcome,
): YoutubeSourceAdapter =
    YoutubeSourceAdapter(
        runner =
            YtDlpProcessRunner(
                object : ProcessRunner {
                    override suspend fun run(launch: ProcessLaunch, onOutputLine: (String) -> Unit): ProcessOutcome =
                        outcome
                },
                Path.of("work"),
            ),
        config = { settings },
        distribution = ManagedBinary(settings.paths.ytDlpBinary, { ytDlpPolicy() }),
    )

/** The process mechanism of a test: it answers the launch with the reply and streams the output lines. */
internal class FakeToolRunner(private val replyOf: (ProcessLaunch) -> ToolReply) : ProcessRunner {

    override suspend fun run(launch: ProcessLaunch, onOutputLine: (String) -> Unit): ProcessOutcome {
        val reply = replyOf(launch)
        if (!reply.exits) return ProcessOutcome.TimedOut
        val lines = reply.output.lines().dropLastWhile(String::isEmpty)
        lines.forEach(onOutputLine)
        return ProcessOutcome.Exited(reply.exitedWith, lines, reply.error)
    }
}
