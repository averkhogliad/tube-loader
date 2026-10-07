package io.averkhogliad.tubeloader.retry

/**
 * What the driver is told beyond the policy: [judging] decides whether the value of an attempt counts
 * as a success, [onRetry] observes a failed attempt before the driver waits for the next one, and
 * [onExhausted] decides what the caller gets once the attempts run out on a value [judging] refused.
 *
 * They travel together rather than as parameters on [retry], whose signature would otherwise pass the
 * limit of the lint configuration. Only the first two answer a question about a single attempt;
 * [onExhausted] answers for the whole run, which is why it sees the refused value and not an attempt.
 */
class RetryContext<T>(
    val judging: (T) -> Boolean = { true },
    val onRetry: ((FailedAttempt) -> Unit)? = null,
    val onExhausted: (T) -> Result<T> = { Result.failure(RetryExhausted(it)) },
)
