package io.averkhogliad.tubeloader.retry

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Repeats [block] while its outcome is refused and [policy] asks for another attempt.
 *
 * An attempt reports its outcome as a value, so the driver never guesses what a failure means. A
 * failure travels to the policy as it is; a value [RetryContext.judging] turns down travels as
 * [RetryExhausted] carrying that value. The driver stops as soon as the policy says so and answers
 * the last outcome — the failure itself, or [RetryExhausted] when the attempts ran out on a value
 * the context kept refusing. Cancellation is rethrown rather than retried: a cancelled caller is not
 * waiting for another attempt.
 */
suspend fun <T> retry(
    policy: RetryPolicy,
    context: RetryContext<T> = RetryContext(),
    block: suspend () -> Result<T>,
): Result<T> {
    var number = 1
    var previousDelay = Duration.ZERO
    var cumulativeDelay = Duration.ZERO
    while (true) {
        val outcome = block()
        val failure =
            outcome.fold(
                onSuccess = { value ->
                    if (context.judging(value)) return outcome
                    RetryExhausted(value)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    error
                },
            )
        val attempt = FailedAttempt(failure, number, previousDelay, cumulativeDelay)
        when (val instruction = policy.decide(attempt)) {
            StopRetrying -> {
                return Result.failure(failure)
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
