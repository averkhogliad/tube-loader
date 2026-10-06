package io.averkhogliad.tubeloader.retry

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Repeats [block] while it answers a failure and [policy] asks for another attempt.
 *
 * An attempt reports its outcome as a value, so the driver never guesses what a failure means: it
 * hands the failure to the policy and stops as soon as the policy says so, answering the last
 * outcome. Cancellation is rethrown rather than retried — a cancelled caller is not waiting for
 * another attempt.
 */
suspend fun <T> retry(policy: RetryPolicy<Throwable>, block: suspend () -> Result<T>): Result<T> {
    var number = 1
    var previousDelay = Duration.ZERO
    var cumulativeDelay = Duration.ZERO
    while (true) {
        val outcome = block()
        val failure = outcome.exceptionOrNull() ?: return outcome
        if (failure is CancellationException) throw failure
        val instruction = policy(FailedAttempt(failure, number, previousDelay, cumulativeDelay))
        when (instruction) {
            StopRetrying -> {
                return outcome
            }

            ContinueRetrying -> {
                previousDelay = Duration.ZERO
            }

            is RetryAfter -> {
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
