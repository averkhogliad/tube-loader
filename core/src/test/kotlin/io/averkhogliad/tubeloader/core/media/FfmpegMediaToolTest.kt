package io.averkhogliad.tubeloader.core.media

import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

private val HOME = Path.of("home")
private val VIDEO = Path.of("in", "video.ts")
private val AUDIO = Path.of("in", "audio.ts")
private val OUTPUT = Path.of("out", "clip.mp4")
private val EXPLICIT_BINARY = Path.of("opt", "bin", "ffmpeg")

/** Records the command instead of starting it: the process itself arrives with the next ticket. */
private class RecordingRunner(private val result: Result<Unit> = Result.success(Unit)) : ProcessRunner {

    val commands = mutableListOf<List<String>>()

    override suspend fun run(command: List<String>): Result<Unit> {
        commands += command
        return result
    }
}

class FfmpegMediaToolTest :
    FreeSpec({

        "mux" - {
            "joins two tracks by copying the streams and cuts at the shorter one" {
                // given
                val runner = RecordingRunner()
                val tool = tool(runner)

                // when
                tool.mux(VIDEO, AUDIO, OUTPUT) {}

                // then
                runner.commands.single() shouldBe
                    listOf(
                        HOME.resolve("ffmpeg").resolve(ffmpegExecutableName()).toString(),
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
                        OUTPUT.toString(),
                    )
            }

            "reports the failure the runner returned" {
                // given
                val failure = UnsupportedOperationException("no tool")
                val tool = tool(RecordingRunner(Result.failure(failure)))

                // when
                val actual = tool.mux(VIDEO, AUDIO, OUTPUT) {}

                // then
                actual.exceptionOrNull() shouldBe failure
            }
        }

        "remux" - {
            "rewrites the container by copying the streams" {
                // given
                val runner = RecordingRunner()
                val tool = tool(runner)

                // when
                tool.remux(VIDEO, OUTPUT) {}

                // then
                runner.commands.single() shouldBe
                    listOf(
                        HOME.resolve("ffmpeg").resolve(ffmpegExecutableName()).toString(),
                        "-y",
                        "-i",
                        VIDEO.toString(),
                        "-c",
                        "copy",
                        "-loglevel",
                        "error",
                        "-hide_banner",
                        OUTPUT.toString(),
                    )
            }

            "reports the failure the runner returned" {
                // given
                val failure = UnsupportedOperationException("no tool")
                val tool = tool(RecordingRunner(Result.failure(failure)))

                // when
                val actual = tool.remux(VIDEO, OUTPUT) {}

                // then
                actual.exceptionOrNull() shouldBe failure
            }
        }

        "binary" - {
            "takes the configured path when the config gives one" {
                // given
                val runner = RecordingRunner()
                val tool = tool(runner, binaryPath = EXPLICIT_BINARY)

                // when
                tool.remux(VIDEO, OUTPUT) {}

                // then
                runner.commands.single().first() shouldBe EXPLICIT_BINARY.toString()
            }

            "falls back to the executable under the home directory of the application" {
                // given
                val runner = RecordingRunner()
                val tool = tool(runner)

                // when
                tool.remux(VIDEO, OUTPUT) {}

                // then
                runner.commands.single().first() shouldBe
                    HOME.resolve("ffmpeg").resolve(ffmpegExecutableName()).toString()
            }

            "names the executable with the extension on Windows and without it elsewhere" {
                // given / when / then
                ffmpegExecutableName("Windows 11") shouldBe "ffmpeg.exe"
                ffmpegExecutableName("Linux") shouldBe "ffmpeg"
                ffmpegExecutableName("Mac OS X") shouldBe "ffmpeg"
            }
        }

        "close" - {
            "is idempotent: closing an already closed tool does not throw" {
                // given
                val tool = tool(RecordingRunner())

                // when
                tool.close()
                val actual: Unit = tool.close()

                // then
                actual shouldBe Unit
            }
        }
    })

private fun tool(runner: ProcessRunner, binaryPath: Path? = null): FfmpegMediaTool =
    FfmpegMediaTool(MediaToolConfig(homeDir = HOME, binaryPath = binaryPath), runner)
