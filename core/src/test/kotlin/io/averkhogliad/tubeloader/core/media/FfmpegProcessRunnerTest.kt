package io.averkhogliad.tubeloader.core.media

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
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.time.Duration.Companion.milliseconds

private const val COMMAND = "ffmpeg"
private const val GRACE = 100L

class FfmpegProcessRunnerTest :
    FreeSpec({

        "run" - {
            "reports success when the tool exits with code zero" {
                // given
                val process = process(exitedWith = 0)

                // when
                val actual = runnerOf(process).run(listOf(COMMAND))

                // then
                actual.isSuccess shouldBe true
            }

            "reports a failure carrying what the tool wrote to the error stream" {
                // given
                val process = process(exitedWith = 1, error = "boom")

                // when
                val actual = runnerOf(process).run(listOf(COMMAND))

                // then
                val failure = actual.exceptionOrNull().shouldBeInstanceOf<ToolFailed>()
                failure.message shouldContain "boom"
            }

            "reports the failure of starting the process itself" {
                // given
                val failure = IOException("cannot start")
                val runner = FfmpegProcessRunner(start = { throw failure })

                // when
                val actual = runner.run(listOf(COMMAND))

                // then
                actual.exceptionOrNull() shouldBe failure
            }

            "hands the command to the process as given" {
                // given
                val process = process(exitedWith = 0)
                val commands = mutableListOf<List<String>>()
                val runner =
                    FfmpegProcessRunner(
                        start = { command ->
                            commands += command
                            process
                        },
                    )

                // when
                runner.run(listOf(COMMAND, "-i", "in.ts"))

                // then
                commands.single() shouldBe listOf(COMMAND, "-i", "in.ts")
            }
        }

        "cancellation" - {
            "kills the tool and its whole tree, then forces nothing when it exits in time" {
                // given
                val child = mockk<ProcessHandle>(relaxed = true)
                val process = process(exitedWith = null, children = listOf(child))

                // when
                coroutineScope {
                    val job = launch { runnerOf(process).run(listOf(COMMAND)) }
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
                    val job = launch { runnerOf(process).run(listOf(COMMAND)) }
                    delay(50.milliseconds)
                    job.cancel()
                    job.join()
                }

                // then
                verify { process.destroyForcibly() }
            }
        }
    })

private fun runnerOf(process: Process): FfmpegProcessRunner =
    FfmpegProcessRunner(start = { process }, graceTimeoutMillis = GRACE)

/**
 * A tool of its own: `exitedWith` null means it never leaves on its own, which is what an operation
 * faces when its coroutine is cancelled. `exitAfterDestroy` tells whether the graceful kill is enough.
 */
private fun process(
    exitedWith: Int?,
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
    every { process.errorStream } returns ByteArrayInputStream(error.toByteArray())
    every { process.descendants() } returns Stream.of(*children.toTypedArray())
    every { process.destroy() } answers {
        killed = true
        if (exitAfterDestroy) pending.forEach { future -> future.complete(process) }
    }
    return process
}
