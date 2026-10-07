package io.averkhogliad.tubeloader.retry

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Repeats [block] while its outcome is refused and [policy] asks for another attempt.
 *
 * An attempt reports its outcome as a value, so the driver never guesses what a failure means. A
 * failure travels to the policy as it is; a value [RetryContext.judging] turns down travels as
 * [RetryExhausted] carrying that value. The driver stops as soon as the policy says so and answers the
 * last outcome: a failure as it came in, and a refused value through [RetryContext.onExhausted], which
 * turns it into [RetryExhausted] unless the caller asks for something else. Cancellation is rethrown
 * rather than retried: a cancelled caller is not waiting for another attempt.
 *
 * [timeSource] is what [FailedAttempt.elapsed] is measured against; a test hands in its own so that a
 * budget can be checked without waiting for real time to pass.
 */
suspend fun <T> retry(
    policy: RetryPolicy,
    context: RetryContext<T> = RetryContext(),
    timeSource: TimeSource = TimeSource.Monotonic,
    block: suspend () -> Result<T>,
): Result<T> {
    val start = timeSource.markNow()
    var number = 1
    var previousDelay = Duration.ZERO
    var cumulativeDelay = Duration.ZERO
    while (true) {
        val outcome = block()
        val failure = outcome.exceptionOrNull()
        val reason: Throwable
        // the answer is built where the value is still typed, so a refused null stays a value
        val answer: () -> Result<T>
        if (failure == null) {
            val value = outcome.getOrThrow()
            if (context.judging(value)) return outcome
            reason = RetryExhausted(value)
            answer = { context.onExhausted(value) }
        } else {
            if (failure is CancellationException) throw failure
            reason = failure
            answer = { Result.failure(failure) }
        }
        val attempt = FailedAttempt(reason, number, previousDelay, cumulativeDelay, start.elapsedNow())
        when (val instruction = policy.decide(attempt)) {
            StopRetrying -> {
                return answer()
            }

            ContinueRetrying -> {
                previousDelay = Duration.ZERO
            }

            is RetryAfter -> {
                context.onRetry?.invoke(attempt)
                previousDelay = instruction.delay
                cumulativeDelay += instruction.delay
                delay(instruction.delay)
                // cancellation has to break the wait here, not survive it into the next attempt
                currentCoroutineContext().ensureActive()
            }
        }
        number += 1
    }
}
