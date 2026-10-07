package io.averkhogliad.tubeloader.retry

/**
 * The reason of an attempt that outlived the budget its caller gave it: the driver catches the
 * timeout thrown around the block and hands this marker to the policy, so a timed-out attempt is
 * retried like any other failure.
 *
 * It is built inside the loop, where the timeout is caught — the opposite of [RetryExhausted], which
 * is built once the loop is over. It must not extend [kotlin.coroutines.cancellation.CancellationException],
 * because the driver rethrows a cancellation before the policy ever sees it.
 */
class AttemptTimedOut(cause: Throwable) : Exception("the attempt outlived its per-attempt budget", cause)
