package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.lang.Process
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val BINARY = "yt-dlp"
private const val GRACE = 100L

internal val YOUTUBE_SETTINGS =
    YtDlpConfig(ffmpegLocation = Path.of("home", "ffmpeg"), binaryPath = Path.of(BINARY))

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
                start = start,
                graceTimeoutMillis = GRACE,
            ),
        config = { settings },
    )

internal fun toolProcess(reply: ToolReply): Process {
    val process = mockk<Process>(relaxed = true)
    val finished = mutableListOf<CompletableFuture<Process>>()
    var killed = false
    every { process.inputStream } returns ByteArrayInputStream(reply.output.toByteArray())
    every { process.errorStream } returns ByteArrayInputStream(reply.error.toByteArray())
    // a wait answers the final state of a process that already left, and stays open otherwise
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
