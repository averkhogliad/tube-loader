package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.domain.fraction
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

private const val MEDIA_ID = "dQw4w9WgXcQ"
private const val VIDEO_BYTES = "bytes"
private const val CANCEL_BUDGET_MILLIS = 200L

private val QUALITY = Quality("720p", TrackKind.Video, "720p")

class YoutubeDownloadTest :
    FreeSpec({
        val roots = mutableListOf<Path>()

        afterSpec {
            roots.forEach { deleteWithRetry(it) }
        }

        "download" - {

            "puts the file the tool produced into the part path of the core" {
                // given
                val world = DownloadWorld(roots).withProducedFile()
                val adapter = world.adapter(ToolReply(output = world.transferOutput()))

                // when
                val actual = adapter.download(MEDIA_ID, QUALITY, world.target) {}

                // then
                actual shouldBe DownloadResult.Success
                Files.readString(world.target) shouldBe VIDEO_BYTES
                Files.exists(world.produced) shouldBe false
            }

            "emits a progress that reaches completion and never goes back" {
                // given
                val world = DownloadWorld(roots).withProducedFile()
                val adapter = world.adapter(ToolReply(output = world.transferOutput()))
                val steps = mutableListOf<Progress>()

                // when
                adapter.download(MEDIA_ID, QUALITY, world.target) { step -> steps += step }

                // then
                steps.first() shouldBe Progress.Indeterminate
                val shares = steps.mapNotNull(Progress::fraction)
                shares.last() shouldBe 1.0
                shares.zipWithNext().all { (before, after) -> before <= after } shouldBe true
            }

            "reports an amount the tool cannot name as indeterminate" {
                // given
                val world = DownloadWorld(roots).withProducedFile()
                val output = world.transferOutput(progress = listOf("dl:@P 100 NA", "dl:@P 200 200"))
                val adapter = world.adapter(ToolReply(output = output))
                val steps = mutableListOf<Progress>()

                // when
                adapter.download(MEDIA_ID, QUALITY, world.target) { step -> steps += step }

                // then
                steps shouldBe
                    listOf(
                        Progress.Indeterminate,
                        Progress.Absolute(200, 200),
                        Progress.Absolute(VIDEO_BYTES.length.toLong(), VIDEO_BYTES.length.toLong()),
                    )
            }

            "picks the format of the chosen quality and points the tool at the bundled ffmpeg" {
                // given
                val world = DownloadWorld(roots).withProducedFile()
                val launches = mutableListOf<YtDlpLaunch>()
                val adapter = world.adapter(ToolReply(output = world.transferOutput()), launches = launches)

                // when
                adapter.download(MEDIA_ID, QUALITY, world.target) {}

                // then
                val command = launches.single().command
                command.first() shouldBe "yt-dlp"
                command[1] shouldBe "--no-config"
                command[command.indexOf("-f") + 1] shouldBe "bv*[height<=?720]+ba/b[height<=?720]"
                command[command.indexOf("--ffmpeg-location") + 1] shouldBe world.bundledFfmpeg().toString()
                command[command.indexOf("-o") + 1].startsWith(world.stagingRoot().toString()) shouldBe true
            }

            "cleans the staging of the tool after the file is placed" {
                // given
                val world = DownloadWorld(roots).withProducedFile()
                val adapter = world.adapter(ToolReply(output = world.transferOutput()))

                // when
                adapter.download(MEDIA_ID, QUALITY, world.target) {}

                // then
                world.stagingEntries().shouldBeEmpty()
            }

            "cleans the staging of the tool when the tool fails" {
                // given
                val world = DownloadWorld(roots)
                val adapter = world.adapter(ToolReply(exitedWith = 1, error = "ERROR: Video unavailable"))

                // when
                val actual = adapter.download(MEDIA_ID, QUALITY, world.target) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.NotFound)
                world.stagingEntries().shouldBeEmpty()
            }

            "cleans the staging of the tool when the call is cancelled" {
                // given
                val world = DownloadWorld(roots)
                val adapter = world.adapter(ToolReply(exits = false))

                // when
                val failure =
                    runCatching {
                        withTimeout(CANCEL_BUDGET_MILLIS) { adapter.download(MEDIA_ID, QUALITY, world.target) {} }
                    }

                // then
                failure.exceptionOrNull().shouldBeInstanceOf<TimeoutCancellationException>()
                world.stagingEntries().shouldBeEmpty()
            }

            "fails with ExtractorBroken when the tool names no file it produced" {
                // given
                val world = DownloadWorld(roots)
                val adapter = world.adapter(ToolReply(output = "dl:@P 200 200"))

                // when
                val actual = adapter.download(MEDIA_ID, QUALITY, world.target) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
            }
        }
    })

/** A home directory of its own, a produced file of its own and the part path the core would hand in. */
private class DownloadWorld(roots: MutableList<Path>) {

    private val root: Path = Files.createTempDirectory("youtube-download").also { roots.add(it) }

    val produced: Path = root.resolve("out").resolve("video.mp4")

    val target: Path = Files.createFile(root.resolve("video.part-1"))

    private val settings = YtDlpConfig(paths = ToolPaths.of(root, "Linux"), binaryPath = Path.of("yt-dlp"))

    fun withProducedFile(): DownloadWorld {
        Files.createDirectories(produced.parent)
        Files.writeString(produced, VIDEO_BYTES)
        return this
    }

    fun bundledFfmpeg(): Path = settings.paths.ffmpegBinary

    fun stagingRoot(): Path = settings.workDirectory

    fun stagingEntries(): List<Path> = Files.list(stagingRoot()).use { entries -> entries.toList() }

    fun adapter(reply: ToolReply, launches: MutableList<YtDlpLaunch> = mutableListOf()): YoutubeSourceAdapter =
        youtubeAdapter(settings = settings, launches = launches, reply = reply)

    fun transferOutput(progress: List<String> = listOf("dl:@P 100 200", "dl:@P 200 200")): String =
        (progress + "fp=$produced").joinToString("\n")
}
