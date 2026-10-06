package io.averkhogliad.tubeloader.retry

import kotlin.time.Duration

/**
 * How far the pause of [exponentialBackoff] may be doubled. Past it the shift would overflow into a
 * negative duration, so a long-lived retry keeps a finite pause instead of an immediate one.
 */
private const val MAX_BACKOFF_STEP = 30

/**
 * Stops once [attempts] attempts have been made, repeating immediately before that.
 */
fun <E> stopAtAttempts(attempts: Int): RetryPolicy<E> {
    require(attempts >= 1) { "a retry needs at least one attempt, got $attempts" }
    return RetryPolicy { attempt -> if (attempt.number >= attempts) StopRetrying else ContinueRetrying }
}

/**
 * Stops as soon as [predicate] turns a failure down.
 */
fun <E> continueIf(predicate: (E) -> Boolean): RetryPolicy<E> =
    RetryPolicy { attempt -> if (predicate(attempt.failure)) ContinueRetrying else StopRetrying }

/**
 * Waits the same [delay] before every attempt.
 */
fun <E> constantDelay(delay: Duration): RetryPolicy<E> = RetryPolicy { RetryAfter(delay) }

/**
 * Doubles the pause with every attempt, starting at [base] and never growing past [limit].
 */
fun <E> exponentialBackoff(base: Duration, limit: Duration = Duration.INFINITE): RetryPolicy<E> =
    RetryPolicy { attempt ->
        val step = (attempt.number - 1).coerceAtMost(MAX_BACKOFF_STEP)
        RetryAfter(minOf(limit, base * (1 shl step)))
    }

/**
 * Stops once the pauses already spent reach [budget].
 *
 * The budget covers the pauses, not the time the attempts themselves take: a policy sees only what
 * the driver reports, and the driver knows just how long it slept.
 */
fun <E> withinBudget(budget: Duration): RetryPolicy<E> =
    RetryPolicy { attempt -> if (attempt.cumulativeDelay >= budget) StopRetrying else ContinueRetrying }

/**
 * Runs both policies on every attempt: a stop on either side stops the retry, and the pause is the
 * longer of the two.
 */
operator fun <E> RetryPolicy<E>.plus(other: RetryPolicy<E>): RetryPolicy<E> =
    RetryPolicy { attempt ->
        val first = this(attempt)
        val second = other(attempt)
        when {
            first is StopRetrying || second is StopRetrying -> StopRetrying
            first is RetryAfter && second is RetryAfter -> RetryAfter(maxOf(first.delay, second.delay))
            first is RetryAfter -> first
            second is RetryAfter -> second
            else -> ContinueRetrying
        }
    }
