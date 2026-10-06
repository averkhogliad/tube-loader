package io.averkhogliad.tubeloader.retry

/**
 * What the driver is told beyond the policy: [judging] decides whether the value of an attempt counts
 * as a success, [onRetry] observes a failed attempt before the driver waits for the next one.
 *
 * Both answer a question about a single attempt, so they travel together: two more parameters on
 * [retry] would put its signature past the limit of the lint configuration.
 */
class RetryContext<T>(val judging: (T) -> Boolean = { true }, val onRetry: ((FailedAttempt) -> Unit)? = null)
