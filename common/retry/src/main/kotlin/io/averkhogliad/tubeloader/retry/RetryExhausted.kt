package io.averkhogliad.tubeloader.retry

/**
 * The terminal outcome of a retry whose attempts ran out while [RetryContext.judging] kept turning
 * down the value the block answered: it carries the last of those values.
 *
 * It is built once the loop is over, never as a way to steer it — a refused value reaches the policy
 * as this reason, and nothing inside the loop throws it.
 */
class RetryExhausted(val lastValue: Any?) : Exception("the retry ran out of attempts with a refused value")
