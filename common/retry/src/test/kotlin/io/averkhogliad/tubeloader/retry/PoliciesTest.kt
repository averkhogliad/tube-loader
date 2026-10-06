package io.averkhogliad.tubeloader.retry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PoliciesTest :
    FreeSpec({

        "stopAtAttempts" - {
            "continues while the attempt is below the limit" {
                // given
                val policy = stopAtAttempts<Throwable>(3)

                // when
                val actual = policy(failedAttempt(number = 2))

                // then
                actual shouldBe ContinueRetrying
            }

            "stops once the limit is reached" {
                // given
                val policy = stopAtAttempts<Throwable>(3)

                // when
                val actual = policy(failedAttempt(number = 3))

                // then
                actual shouldBe StopRetrying
            }

            "rejects a limit below one attempt" {
                // when
                val thrown = shouldThrow<IllegalArgumentException> { stopAtAttempts<Throwable>(0) }

                // then
                thrown.message shouldBe "a retry needs at least one attempt, got 0"
            }
        }

        "continueIf" - {
            "continues a failure the predicate accepts" {
                // given
                val policy = continueIf<Throwable> { it is IOException }

                // when
                val actual = policy(failedAttempt(number = 1, failure = IOException("reset")))

                // then
                actual shouldBe ContinueRetrying
            }

            "stops a failure the predicate turns down" {
                // given
                val policy = continueIf<Throwable> { it is IOException }

                // when
                val actual = policy(failedAttempt(number = 1, failure = IllegalStateException("broken")))

                // then
                actual shouldBe StopRetrying
            }
        }

        "constantDelay" - {
            "asks for the same pause on every attempt" {
                // given
                val policy = constantDelay<Throwable>(250.milliseconds)

                // when
                val first = policy(failedAttempt(number = 1))
                val last = policy(failedAttempt(number = 9))

                // then
                first shouldBe RetryAfter(250.milliseconds)
                last shouldBe RetryAfter(250.milliseconds)
            }
        }

        "exponentialBackoff" - {
            "doubles the pause with every attempt" {
                // given
                val policy = exponentialBackoff<Throwable>(250.milliseconds)

                // when
                val pauses = (1..5).map { policy(failedAttempt(number = it)) }

                // then
                pauses shouldBe listOf(250, 500, 1000, 2000, 4000).map { RetryAfter(it.milliseconds) }
            }

            "never grows past the limit" {
                // given
                val policy = exponentialBackoff<Throwable>(250.milliseconds, limit = 1.seconds)

                // when
                val pauses = (1..4).map { policy(failedAttempt(number = it)) }

                // then
                pauses shouldBe listOf(250, 500, 1000, 1000).map { RetryAfter(it.milliseconds) }
            }
        }

        "withinBudget" - {
            "continues while the pauses spent stay below the budget" {
                // given
                val policy = withinBudget<Throwable>(1.seconds)

                // when
                val actual = policy(failedAttempt(number = 1, cumulativeDelay = 900.milliseconds))

                // then
                actual shouldBe ContinueRetrying
            }

            "stops once the pauses spent reach the budget" {
                // given
                val policy = withinBudget<Throwable>(1.seconds)

                // when
                val actual = policy(failedAttempt(number = 2, cumulativeDelay = 1.seconds))

                // then
                actual shouldBe StopRetrying
            }

            "never stops when the budget is infinite" {
                // given
                val policy = withinBudget<Throwable>(kotlin.time.Duration.INFINITE)

                // when
                val actual = policy(failedAttempt(number = 99, cumulativeDelay = 365.milliseconds * 24 * 3600))

                // then
                actual shouldBe ContinueRetrying
            }
        }

        "plus" - {
            "stops when either side stops" {
                // given
                val attempts = stopAtAttempts<Throwable>(5)
                val predicate = continueIf<Throwable> { it is IOException }

                // when
                val stopped = (attempts + predicate)(failedAttempt(number = 1, failure = IllegalStateException()))
                val exhausted = (attempts + predicate)(failedAttempt(number = 5))

                // then
                stopped shouldBe StopRetrying
                exhausted shouldBe StopRetrying
            }

            "waits the longer of the two pauses" {
                // given
                val slow = constantDelay<Throwable>(2.seconds)
                val fast = constantDelay<Throwable>(250.milliseconds)

                // when
                val actual = (slow + fast)(failedAttempt(number = 1))

                // then
                actual shouldBe RetryAfter(2.seconds)
            }

            "keeps the pause one side asks for when the other repeats at once" {
                // given
                val pausing = constantDelay<Throwable>(2.seconds)
                val immediate = stopAtAttempts<Throwable>(5)

                // when
                val actual = (pausing + immediate)(failedAttempt(number = 1))

                // then
                actual shouldBe RetryAfter(2.seconds)
            }
        }
    })
