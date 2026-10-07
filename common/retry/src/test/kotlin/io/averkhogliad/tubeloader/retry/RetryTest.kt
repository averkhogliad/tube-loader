package io.averkhogliad.tubeloader.retry

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

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

            "counts the budget in elapsed time, not in the pauses it slept" {
                runTest {
                    // given a source that takes twelve seconds to answer and then refuses
                    val timeSource = TestTimeSource()
                    val budget = 30.seconds
                    val calls = AtomicInteger()

                    // when
                    val actual =
                        retry(RetryPolicy.withinBudget(budget), timeSource = timeSource) {
                            calls.incrementAndGet()
                            timeSource += 12.seconds
                            Result.failure(IOException(UNREACHABLE))
                        }

                    // then three attempts fit in the budget and the fourth does not
                    actual.isFailure shouldBe true
                    calls.get() shouldBe 3
                }
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

        "judging" - {
            "accepts the value of the first attempt and never asks for another one" {
                // given
                val attempts = Attempts(failures = 0)
                val judged = mutableListOf<String>()
                val retries = mutableListOf<FailedAttempt>()
                val context =
                    RetryContext<String>(
                        judging = { value ->
                            judged += value
                            true
                        },
                        onRetry = { retries += it },
                    )

                // when
                val actual = retry(RetryPolicy.stopAtAttempts(3), context) { attempts.answer("payload") }

                // then
                actual shouldBe Result.success("payload")
                judged shouldBe listOf("payload")
                retries shouldBe emptyList()
            }

            "repeats a value the context turns down" {
                // given
                val values = listOf("broken", "broken", "payload")
                var calls = 0
                val context = RetryContext<String>(judging = { it == "payload" })

                // when
                val actual =
                    retry(RetryPolicy.stopAtAttempts(5), context) { Result.success(values[calls++]) }

                // then
                actual shouldBe Result.success("payload")
                calls shouldBe 3
            }

            "answers a terminal failure carrying the last refused value when the attempts run out" {
                // given
                val attempts = Attempts(failures = 0)
                val context = RetryContext<String>(judging = { false })

                // when
                val actual = retry(RetryPolicy.stopAtAttempts(3), context) { attempts.answer("broken") }

                // then
                actual.isFailure shouldBe true
                actual.exceptionOrNull().shouldBeInstanceOf<RetryExhausted>().lastValue shouldBe "broken"
                attempts.calls shouldBe 3
            }

            "answers the transport failure itself when the attempts run out on it" {
                // given
                val attempts = Attempts(failures = 5)
                val context = RetryContext<String>(judging = { false })

                // when
                val actual = retry(RetryPolicy.stopAtAttempts(2), context) { attempts.answer("payload") }

                // then
                actual.exceptionOrNull().shouldBeInstanceOf<IOException>()
            }

            "keeps the previous behaviour when no context is given" {
                // given
                val attempts = Attempts(failures = 1)

                // when
                val actual = retry(RetryPolicy.stopAtAttempts(5)) { attempts.answer("payload") }

                // then
                actual shouldBe Result.success("payload")
                attempts.calls shouldBe 2
            }
        }

        "onRetry" - {
            "is told about the attempt before every pause the driver takes" {
                runTest {
                    // given
                    val attempts = Attempts(failures = 5)
                    val seen = mutableListOf<Triple<Int, Duration, Long>>()
                    val context =
                        RetryContext<String>(
                            onRetry = { attempt ->
                                seen += Triple(attempt.number, attempt.cumulativeDelay, testScheduler.currentTime)
                            },
                        )

                    // when
                    retry(RetryPolicy.stopAtAttempts(3).constantDelay(100.milliseconds), context) {
                        attempts.answer("payload")
                    }

                    // then
                    seen.map { it.first } shouldBe listOf(1, 2)
                    seen.map { it.second } shouldBe listOf(Duration.ZERO, 100.milliseconds)
                    seen.map { it.third } shouldBe listOf(0L, 100L)
                    testScheduler.currentTime shouldBe 200L
                }
            }

            "never fires when the policy stops without asking for a pause" {
                // given
                val attempts = Attempts(failures = 5)
                val seen = mutableListOf<FailedAttempt>()
                val context = RetryContext<String>(onRetry = { seen += it })

                // when
                retry(RetryPolicy.stopAtAttempts(1), context) { attempts.answer("payload") }

                // then
                seen shouldBe emptyList()
            }
        }
    })
