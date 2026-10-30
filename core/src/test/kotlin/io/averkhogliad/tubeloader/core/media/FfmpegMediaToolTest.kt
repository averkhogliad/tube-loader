package io.averkhogliad.tubeloader.core.media

import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.deleteWithRetry
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.port.MediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

private val VIDEO = Path.of("in", "video.ts")
private val AUDIO = Path.of("in", "audio.ts")
private val OUTPUT = Path.of("out", "clip.mp4")

/** Records the command instead of starting it: the process itself is covered by the integration spec. */
private class RecordingRunner(private val result: Result<Unit> = Result.success(Unit)) : ProcessRunner {

    val commands = mutableListOf<List<String>>()

    override suspend fun run(command: List<String>): Result<Unit> {
        commands += command
        return result
    }
}

/** A random home directory per case, wiped once the spec ends. */
private class TempHome {

    private val root: Path = Files.createTempDirectory("ffmpeg-media-tool")

    fun home(): Path = Files.createTempDirectory(root, "home")

    /** The path the config injects: the tool must run it and never fetch anything. */
    fun binary(): Path =
        Files.createTempFile(root, "ffmpeg", ".stub").also {
            it.writeText("stub")
        }

    suspend fun close() {
        deleteWithRetry(root)
    }
}

/** The tool under test with an injected binary, so no case reaches the network. */
private class MediaToolUnderTest(temp: TempHome, runner: ProcessRunner) {

    val binary: Path = temp.binary()
    val config = MediaToolConfig(paths = ToolPaths.of(temp.home()), binaryPath = binary)
    val tool: MediaTool = FfmpegMediaTool(config, runner)
}

class FfmpegMediaToolTest :
    FreeSpec({

        lateinit var temp: TempHome

        beforeSpec { temp = TempHome() }

        afterSpec { temp.close() }

        "mux" - {
            "joins two tracks by copying the streams and cuts at the shorter one" {
                // given
                val runner = RecordingRunner()
                val underTest = MediaToolUnderTest(temp, runner)

                // when
                underTest.tool.mux(VIDEO, AUDIO, OUTPUT) {}

                // then
                runner.commands.single() shouldBe
                    listOf(
                        underTest.binary.toString(),
                        "-y",
                        "-i",
                        VIDEO.toString(),
                        "-i",
                        AUDIO.toString(),
                        "-c",
                        "copy",
                        "-shortest",
                        "-loglevel",
                        "error",
                        "-hide_banner",
                        "-f",
                        "mp4",
                        OUTPUT.toString(),
                    )
            }

            "reports the indeterminate progress before the tool starts" {
                // given
                val runner = RecordingRunner()
                val underTest = MediaToolUnderTest(temp, runner)
                val progress = mutableListOf<Progress>()

                // when
                underTest.tool.mux(VIDEO, AUDIO, OUTPUT) { update -> progress += update }

                // then
                progress shouldBe listOf(Progress.Indeterminate)
            }

            "reports the failure the runner returned" {
                // given
                val failure = ToolFailed(1, "boom")
                val underTest = MediaToolUnderTest(temp, RecordingRunner(Result.failure(failure)))

                // when
                val actual = underTest.tool.mux(VIDEO, AUDIO, OUTPUT) {}

                // then
                actual.exceptionOrNull() shouldBe failure
            }
        }

        "remux" - {
            "rewrites the container by copying the streams" {
                // given
                val runner = RecordingRunner()
                val underTest = MediaToolUnderTest(temp, runner)

                // when
                underTest.tool.remux(VIDEO, OUTPUT) {}

                // then
                runner.commands.single() shouldBe
                    listOf(
                        underTest.binary.toString(),
                        "-y",
                        "-i",
                        VIDEO.toString(),
                        "-c",
                        "copy",
                        "-loglevel",
                        "error",
                        "-hide_banner",
                        "-f",
                        "mp4",
                        OUTPUT.toString(),
                    )
            }

            "reports the failure the runner returned" {
                // given
                val failure = ToolFailed(1, "boom")
                val underTest = MediaToolUnderTest(temp, RecordingRunner(Result.failure(failure)))

                // when
                val actual = underTest.tool.remux(VIDEO, OUTPUT) {}

                // then
                actual.exceptionOrNull() shouldBe failure
            }
        }

        "container format" - {
            "takes the format from the config instead of the extension of the output" {
                // given
                val runner = RecordingRunner()
                val binary = temp.binary()
                val config =
                    MediaToolConfig(
                        paths = ToolPaths.of(temp.home()),
                        binaryPath = binary,
                        containerFormat = "mkv",
                    )
                val tool = FfmpegMediaTool(config, runner)

                // when
                tool.remux(VIDEO, OUTPUT) {}

                // then
                val command = runner.commands.single()
                command[command.lastIndexOf("-f") + 1] shouldBe "mkv"
            }

            "defaults to mp4" {
                // given
                val runner = RecordingRunner()
                val underTest = MediaToolUnderTest(temp, runner)

                // when
                underTest.tool.remux(VIDEO, OUTPUT) {}

                // then
                val command = runner.commands.single()
                command[command.lastIndexOf("-f") + 1] shouldBe "mp4"
            }
        }

        "binary" - {
            "runs the path the config injected and never looks into the home directory" {
                // given
                val runner = RecordingRunner()
                val home = temp.home()
                val injected = temp.binary()
                val tool = FfmpegMediaTool(MediaToolConfig(paths = ToolPaths.of(home), binaryPath = injected), runner)

                // when
                tool.remux(VIDEO, OUTPUT) {}

                // then
                runner.commands.single().first() shouldBe injected.toString()
                home.resolve(ToolPaths.FFMPEG_DIR).exists() shouldBe false
            }

            "reuses the tool already unpacked in the home directory and downloads nothing" {
                // given
                val runner = RecordingRunner()
                val paths = ToolPaths.of(temp.home())
                val managed =
                    paths.ffmpegBinary.apply {
                        parent.toFile().mkdirs()
                        writeText("stub")
                        toFile().setExecutable(true, false)
                    }
                val tool = FfmpegMediaTool(MediaToolConfig(paths = paths), runner)

                // when
                tool.remux(VIDEO, OUTPUT) {}

                // then
                runner.commands.single().first() shouldBe managed.toString()
            }
        }

        "close" - {
            "is idempotent: closing an already closed tool does not throw" {
                // given
                val underTest = MediaToolUnderTest(temp, RecordingRunner())

                // when
                underTest.tool.close()
                val actual: Unit = underTest.tool.close()

                // then
                actual shouldBe Unit
            }
        }
    })
