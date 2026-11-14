package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.infra.binaries.ProcessLaunch
import io.averkhogliad.tubeloader.infra.binaries.ProcessOutcome
import io.averkhogliad.tubeloader.infra.binaries.ProcessRunner
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private const val BINARY = "yt-dlp"
private const val PYTHON_UTF8 = "PYTHONUTF8"
private const val UNAVAILABLE = "ERROR: Video unavailable"

private val WORKING_DIRECTORY: Path = Path.of("work")

class YtDlpProcessRunnerTest :
    FreeSpec({

        "run" - {
            "returns the lines the tool wrote to its output stream" {
                // given
                val fake = FakeProcessRunner(lines = listOf("first", "second"))

                // when
                val actual = runnerOf(fake).run(listOf(BINARY))

                // then
                actual.getOrThrow().output shouldBe listOf("first", "second")
            }

            "streams the output lines to the caller as they come" {
                // given
                val fake = FakeProcessRunner(lines = listOf("first", "second"))
                val streamed = mutableListOf<String>()

                // when
                runnerOf(fake).run(listOf(BINARY), onOutputLine = streamed::add)

                // then
                streamed shouldBe listOf("first", "second")
            }

            "reports success when the tool exits with code zero" {
                // given
                val fake = FakeProcessRunner()

                // when
                val actual = runnerOf(fake).run(listOf(BINARY))

                // then
                actual.isSuccess shouldBe true
            }

            "reports the failure carrying what the tool wrote to the error stream" {
                // given
                val fake =
                    FakeProcessRunner(
                        lines = listOf("first"),
                        outcome = { output -> ProcessOutcome.Exited(1, output, UNAVAILABLE) },
                    )

                // when
                val actual = runnerOf(fake).run(listOf(BINARY))

                // then
                val failure = actual.exceptionOrNull().shouldBeInstanceOf<YtDlpFailed>()
                failure.toolMessage shouldContain UNAVAILABLE
            }

            "treats an error on the error stream as a failure even when the tool exited with zero" {
                // given
                val fake = FakeProcessRunner(outcome = { output -> ProcessOutcome.Exited(0, output, UNAVAILABLE) })

                // when
                val actual = runnerOf(fake).run(listOf(BINARY))

                // then
                actual.exceptionOrNull().shouldBeInstanceOf<YtDlpFailed>()
            }

            "reports the failure of starting the process itself" {
                // given
                val failure = IOException("cannot start")
                val fake = FakeProcessRunner(outcome = { ProcessOutcome.SpawnFailed(failure) })

                // when
                val actual = runnerOf(fake).run(listOf(BINARY))

                // then
                actual.exceptionOrNull() shouldBe failure
            }

            "hands the command over with the config disabled right after the binary" {
                // given
                val fake = FakeProcessRunner()

                // when
                runnerOf(fake).run(listOf(BINARY, "-J", "--skip-download"))

                // then
                fake.launches.single().command shouldBe listOf(BINARY, "--no-config", "-J", "--skip-download")
            }

            "runs the tool in the controlled directory with the whitelisted environment" {
                // given
                val fake = FakeProcessRunner()

                // when
                runnerOf(fake).run(listOf(BINARY))

                // then
                val launch = fake.launches.single()
                launch.workingDirectory shouldBe WORKING_DIRECTORY
                launch.environment!![PYTHON_UTF8] shouldBe "1"
                launch.environment!!.containsKey("PYTHONPATH") shouldBe false
                launch.environment!!.containsKey("PYTHONHOME") shouldBe false
            }

            "passes the budget of the call to the process mechanism" {
                // given
                val fake = FakeProcessRunner()

                // when
                runnerOf(fake).run(listOf(BINARY), timeout = 50.milliseconds)

                // then
                fake.launches.single().timeout shouldBe 50.milliseconds
            }
        }

        "timeout" - {
            "returns a timeout outcome instead of waiting for the tool forever" {
                // given
                val fake = FakeProcessRunner(outcome = { ProcessOutcome.TimedOut })

                // when
                val actual = runnerOf(fake).run(listOf(BINARY), timeout = 50.milliseconds)

                // then
                actual.exceptionOrNull().shouldBeInstanceOf<YtDlpTimedOut>()
            }
        }

        "withNoConfig" - {
            "puts the option right after the binary" {
                // when
                val actual = withNoConfig(listOf(BINARY, "-J"))

                // then
                actual shouldBe listOf(BINARY, "--no-config", "-J")
            }

            "keeps a single option when the command names it already" {
                // when
                val actual = withNoConfig(listOf(BINARY, "--no-config", "-J"))

                // then
                actual shouldBe listOf(BINARY, "--no-config", "-J")
            }
        }

        "ytDlpEnvironment" - {
            "forces the python text encoding" {
                // when
                val actual = ytDlpEnvironment(emptyMap())

                // then
                actual[PYTHON_UTF8] shouldBe "1"
            }

            "drops the python paths and everything outside the whitelist" {
                // given
                val inherited = mapOf("PYTHONPATH" to "/tmp/one", "PYTHONHOME" to "/tmp/two", "SECRET" to "value")

                // when
                val actual = ytDlpEnvironment(inherited)

                // then
                actual.keys.any { name -> name.equals("PYTHONPATH", ignoreCase = true) } shouldBe false
                actual.keys.any { name -> name.equals("PYTHONHOME", ignoreCase = true) } shouldBe false
                actual.containsKey("SECRET") shouldBe false
            }

            "keeps the variables the tool needs to start on its platform" {
                // given
                val inherited = mapOf("Path" to "/usr/bin", "SystemRoot" to "C:\\Windows")

                // when
                val actual = ytDlpEnvironment(inherited)

                // then
                actual["Path"] shouldBe "/usr/bin"
                actual["SystemRoot"] shouldBe "C:\\Windows"
            }
        }
    })

private fun runnerOf(fake: FakeProcessRunner = FakeProcessRunner()): YtDlpProcessRunner =
    YtDlpProcessRunner(fake, WORKING_DIRECTORY)

/** The process mechanism of a test: records the launch, streams [lines] and answers [outcome]. */
private class FakeProcessRunner(
    private val lines: List<String> = emptyList(),
    private val outcome: (List<String>) -> ProcessOutcome = { output -> ProcessOutcome.Exited(0, output, "") },
) : ProcessRunner {

    val launches = mutableListOf<ProcessLaunch>()

    override suspend fun run(launch: ProcessLaunch, onOutputLine: (String) -> Unit): ProcessOutcome {
        launches += launch
        lines.forEach(onOutputLine)
        return outcome(lines)
    }
}
