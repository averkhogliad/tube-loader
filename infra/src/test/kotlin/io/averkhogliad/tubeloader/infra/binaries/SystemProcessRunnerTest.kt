package io.averkhogliad.tubeloader.infra.binaries

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

private const val COMMAND = "tool"
private const val GRACE = 100L
private val WORKING_DIRECTORY: Path = Path.of("work")

private val LAUNCH = ProcessLaunch(command = listOf(COMMAND))

class SystemProcessRunnerTest :
    FreeSpec({

        "run" - {
            "reports the exit code, the output and the error text of the tool" {
                // given
                val process = process(exitedWith = 0, output = "first\nsecond", error = "problem")

                // when
                val actual = runnerOf(process).run(LAUNCH)

                // then
                val exited = actual.shouldBeInstanceOf<ProcessOutcome.Exited>()
                exited.exitCode shouldBe 0
                exited.output shouldBe listOf("first", "second")
                exited.errorText shouldContain "problem"
            }

            "streams the output lines to the caller as they come" {
                // given
                val process = process(exitedWith = 0, output = "first\nsecond")
                val streamed = mutableListOf<String>()

                // when
                runnerOf(process).run(LAUNCH, onOutputLine = streamed::add)

                // then
                streamed shouldBe listOf("first", "second")
            }

            "keeps the code of a failed tool as an outcome, not as an error of the mechanism" {
                // given
                val process = process(exitedWith = 1, output = "first", error = "boom")

                // when
                val actual = runnerOf(process).run(LAUNCH)

                // then
                val exited = actual.shouldBeInstanceOf<ProcessOutcome.Exited>()
                exited.exitCode shouldBe 1
                exited.errorText shouldContain "boom"
            }

            "reports the failure of starting the process itself" {
                // given
                val failure = IOException("cannot start")
                val runner = SystemProcessRunner(start = { throw failure })

                // when
                val actual = runner.run(LAUNCH)

                // then
                actual.shouldBeInstanceOf<ProcessOutcome.SpawnFailed>().cause shouldBe failure
            }

            "hands the launch to the process as given" {
                // given
                val process = process(exitedWith = 0)
                val launches = mutableListOf<ProcessLaunch>()
                val runner =
                    SystemProcessRunner(
                        start = { launch ->
                            launches += launch
                            process
                        },
                    )

                // when
                runner.run(ProcessLaunch(listOf(COMMAND, "-i", "in.ts"), WORKING_DIRECTORY, mapOf("A" to "B")))

                // then
                val launch = launches.single()
                launch.command shouldBe listOf(COMMAND, "-i", "in.ts")
                launch.workingDirectory shouldBe WORKING_DIRECTORY
                launch.environment shouldBe mapOf("A" to "B")
            }

            "keeps only the head of the output past the limit" {
                // given
                val process = process(exitedWith = 0, output = "one\ntwo\nthree")
                val streamed = mutableListOf<String>()

                // when
                val actual = runnerOf(process, outputLimitLines = 2).run(LAUNCH, onOutputLine = streamed::add)

                // then
                actual.shouldBeInstanceOf<ProcessOutcome.Exited>().output shouldBe listOf("one", "two")
                streamed shouldBe listOf("one", "two", "three")
            }

            "keeps only the head of the error stream past the limit" {
                // given
                val process = process(exitedWith = 0, error = "aaa\nbbb\nccc")

                // when
                val actual = runnerOf(process, errorLimitChars = 5).run(LAUNCH)

                // then
                val errorText = actual.shouldBeInstanceOf<ProcessOutcome.Exited>().errorText
                errorText shouldContain "bbb"
                errorText shouldNotContain "ccc"
            }
        }

        "timeout" - {
            "returns a timeout outcome and kills the tool instead of waiting for it forever" {
                // given
                val process = process(exitedWith = null)

                // when
                val actual = runnerOf(process).run(LAUNCH.copy(timeout = 50.milliseconds))

                // then
                actual shouldBe ProcessOutcome.TimedOut
                verify { process.destroy() }
            }
        }

        "cancellation" - {
            "kills the tool and its whole tree, then forces nothing when it exits in time" {
                // given
                val child = mockk<ProcessHandle>(relaxed = true)
                val process = process(exitedWith = null, children = listOf(child))

                // when
                coroutineScope {
                    val job = launch { runnerOf(process).run(LAUNCH) }
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
                    val job = launch { runnerOf(process).run(LAUNCH) }
                    delay(50.milliseconds)
                    job.cancel()
                    job.join()
                }

                // then
                verify { process.destroyForcibly() }
            }
        }
    })

private fun runnerOf(
    process: Process,
    outputLimitLines: Int = SystemProcessRunner.OUTPUT_LIMIT_LINES,
    errorLimitChars: Int = SystemProcessRunner.ERROR_LIMIT_CHARS,
): SystemProcessRunner =
    SystemProcessRunner(
        start = { process },
        graceTimeout = GRACE.milliseconds,
        outputLimitLines = outputLimitLines,
        errorLimitChars = errorLimitChars,
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
