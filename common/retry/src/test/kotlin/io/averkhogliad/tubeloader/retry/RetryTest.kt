package io.averkhogliad.tubeloader.retry

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest :
    FreeSpec({

        "retry" - {
            "answers the first success without asking the policy" {
                // given
                val attempts = Attempts(failures = 0)
                val recording = RecordingPolicy { ContinueRetrying }
                val policy = RetryPolicy.then(recording)

                // when
                val actual = retry(policy) { attempts.answer("payload") }

                // then
                actual shouldBe Result.success("payload")
                attempts.calls shouldBe 1
                recording.seen shouldBe emptyList()
            }

            "repeats the block while the policy continues" {
                // given
                val attempts = Attempts(failures = 2)
                val policy = RetryPolicy.stopAtAttempts(5)

                // when
                val actual = retry(policy) { attempts.answer("payload") }

                // then
                actual shouldBe Result.success("payload")
                attempts.calls shouldBe 3
            }

            "answers the last failure as soon as the policy stops" {
                // given
                val attempts = Attempts(failures = 5)
                val policy = RetryPolicy.stopAtAttempts(2)

                // when
                val actual = retry(policy) { attempts.answer("payload") }

                // then
                actual.isFailure shouldBe true
                attempts.calls shouldBe 2
            }

            "waits for the pause the policy asks for" {
                // given
                val attempts = Attempts(failures = 2)
                val policy = RetryPolicy.stopAtAttempts(3).constantDelay(100.milliseconds)
                var spent = 0L

                // when
                runTest {
                    retry(policy) { attempts.answer("payload") }
                    spent = testScheduler.currentTime
                }

                // then
                spent shouldBe 200L
            }

            "hands the attempt number and the delays it spent to the policy" {
                // given
                val attempts = Attempts(failures = 3)
                val recording =
                    RecordingPolicy { attempt ->
                        if (attempt.number >= 3) StopRetrying else RetryAfter(100.milliseconds)
                    }
                val policy = RetryPolicy.then(recording)
                val previousDelays = listOf(Duration.ZERO, 100.milliseconds, 100.milliseconds)
                val cumulativeDelays = listOf(Duration.ZERO, 100.milliseconds, 200.milliseconds)

                // when
                runTest { retry(policy) { attempts.answer("payload") } }

                // then
                recording.seen.map { it.number } shouldBe listOf(1, 2, 3)
                recording.seen.map { it.previousDelay } shouldBe previousDelays
                recording.seen.map { it.cumulativeDelay } shouldBe cumulativeDelays
            }

            "rethrows cancellation without asking the policy" {
                // given
                val recording = RecordingPolicy { ContinueRetrying }
                val policy = RetryPolicy.then(recording)

                // when
                val thrown =
                    runCatching {
                        runTest { retry<String>(policy) { throw CancellationException("cancelled") } }
                    }.exceptionOrNull()

                // then
                thrown.shouldBeInstanceOf<CancellationException>()
                recording.seen shouldBe emptyList()
            }
        }
    })
