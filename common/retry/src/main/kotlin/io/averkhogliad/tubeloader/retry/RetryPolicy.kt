package io.averkhogliad.tubeloader.retry

/**
 * Decides whether a failed attempt is worth repeating and how long to wait first.
 *
 * A policy is a pure function of the attempt it is handed: it holds no state, so the same policy can
 * serve any number of concurrent retries. Policies compose with `+`; see [plus].
 */
fun interface RetryPolicy<E> {

    operator fun invoke(attempt: FailedAttempt<E>): RetryInstruction
}
