package io.averkhogliad.tubeloader.adapters.youtube

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.Process
import java.lang.ProcessHandle
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.time.Duration.Companion.milliseconds

private const val BINARY = "yt-dlp"
private const val GRACE = 100L
private const val PYTHON_UTF8 = "PYTHONUTF8"
private const val UNAVAILABLE = "ERROR: Video unavailable"

private val WORKING_DIRECTORY: Path = Path.of("work")

class YtDlpProcessRunnerTest :
    FreeSpec({

        "run" - {
            "returns the lines the tool wrote to its output stream" {
                // given
                val process = process(exitedWith = 0, output = "first\nsecond")

                // when
                val actual = runnerOf(process).run(listOf(BINARY)).getOrThrow()

                // then
                actual.output shouldBe listOf("first", "second")
            }

            "streams the output lines to the caller as they come" {
                // given
                val process = process(exitedWith = 0, output = "first\nsecond")
                val streamed = mutableListOf<String>()

                // when
                runnerOf(process).run(listOf(BINARY), onOutputLine = streamed::add)

                // then
                streamed shouldBe listOf("first", "second")
            }

            "reports success when the tool exits with code zero" {
                // given
                val process = process(exitedWith = 0)

                // when
                val actual = runnerOf(process).run(listOf(BINARY))

                // then
                actual.isSuccess shouldBe true
            }

            "reports the failure carrying what the tool wrote to the error stream" {
                // given
                val process = process(exitedWith = 1, error = UNAVAILABLE)

                // when
                val actual = runnerOf(process).run(listOf(BINARY))

                // then
                val failure = actual.exceptionOrNull().shouldBeInstanceOf<YtDlpFailed>()
                failure.toolMessage shouldContain UNAVAILABLE
            }

            "treats an error on the error stream as a failure even when the tool exited with zero" {
                // given
                val process = process(exitedWith = 0, error = UNAVAILABLE)

                // when
                val actual = runnerOf(process).run(listOf(BINARY))

                // then
                actual.exceptionOrNull().shouldBeInstanceOf<YtDlpFailed>()
            }

            "reports the failure of starting the process itself" {
                // given
                val failure = IOException("cannot start")
                val runner = YtDlpProcessRunner(workingDirectory = WORKING_DIRECTORY, start = { throw failure })

                // when
                val actual = runner.run(listOf(BINARY))

                // then
                actual.exceptionOrNull() shouldBe failure
            }

            "hands the command over with the config disabled right after the binary" {
                // given
                val launches = mutableListOf<YtDlpLaunch>()

                // when
                runnerOf(process(exitedWith = 0), launches).run(listOf(BINARY, "-J", "--skip-download"))

                // then
                launches.single().command shouldBe listOf(BINARY, "--no-config", "-J", "--skip-download")
            }

            "runs the tool in the controlled directory with the whitelisted environment" {
                // given
                val launches = mutableListOf<YtDlpLaunch>()

                // when
                runnerOf(process(exitedWith = 0), launches).run(listOf(BINARY))

                // then
                val launch = launches.single()
                launch.workingDirectory shouldBe WORKING_DIRECTORY
                launch.environment[PYTHON_UTF8] shouldBe "1"
                launch.environment.containsKey("PYTHONPATH") shouldBe false
                launch.environment.containsKey("PYTHONHOME") shouldBe false
            }
        }

        "timeout" - {
            "returns a timeout outcome and kills the tool instead of waiting for it forever" {
                // given
                val process = process(exitedWith = null)

                // when
                val actual = runnerOf(process).run(listOf(BINARY), timeout = 50.milliseconds)

                // then
                actual.exceptionOrNull().shouldBeInstanceOf<YtDlpTimedOut>()
                verify { process.destroy() }
            }
        }

        "cancellation" - {
            "kills the tool and its whole tree within the grace period" {
                // given
                val child = mockk<ProcessHandle>(relaxed = true)
                val process = process(exitedWith = null, children = listOf(child))

                // when
                coroutineScope {
                    val job = launch { runnerOf(process).run(listOf(BINARY)) }
                    delay(50.milliseconds)
                    job.cancel()
                    job.join()
                }

                // then
                verify { child.destroy() }
                verify { process.destroy() }
                verify(exactly = 0) { process.destroyForcibly() }
            }

            "forces the tool when it ignores the graceful exit past the grace period" {
                // given
                val process = process(exitedWith = null, exitAfterDestroy = false)

                // when
                coroutineScope {
                    val job = launch { runnerOf(process).run(listOf(BINARY)) }
                    delay(50.milliseconds)
                    job.cancel()
                    job.join()
                }

                // then
                verify { process.destroyForcibly() }
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

private fun runnerOf(process: Process, launches: MutableList<YtDlpLaunch> = mutableListOf()): YtDlpProcessRunner =
    YtDlpProcessRunner(
        workingDirectory = WORKING_DIRECTORY,
        start = { launch ->
            launches += launch
            process
        },
        graceTimeoutMillis = GRACE,
    )

/**
 * A tool of its own: `exitedWith` null means it never leaves on its own, which is what an operation
 * faces when its coroutine is cancelled or its budget runs out. `exitAfterDestroy` tells whether the
 * graceful kill is enough.
 */
private fun process(
    exitedWith: Int?,
    output: String = "",
    error: String = "",
    children: List<ProcessHandle> = emptyList(),
    exitAfterDestroy: Boolean = true,
): Process {
    val process = mockk<Process>(relaxed = true)
    val pending = mutableListOf<CompletableFuture<Process>>()
    var killed = false

    // the process of a real tool answers a wait that starts after the kill with its final state, not
    // with a future that never ends: a fresh future per call would report a false timeout otherwise
    every { process.onExit() } answers {
        CompletableFuture<Process>().also { future ->
            if (exitedWith != null || (killed && exitAfterDestroy)) future.complete(process)
            pending += future
        }
    }
    every { process.exitValue() } returns (exitedWith ?: 0)
    every { process.inputStream } returns ByteArrayInputStream(output.toByteArray())
    every { process.errorStream } returns ByteArrayInputStream(error.toByteArray())
    every { process.descendants() } returns Stream.of(*children.toTypedArray())
    every { process.destroy() } answers {
        killed = true
        if (exitAfterDestroy) pending.forEach { future -> future.complete(process) }
    }
    return process
}
