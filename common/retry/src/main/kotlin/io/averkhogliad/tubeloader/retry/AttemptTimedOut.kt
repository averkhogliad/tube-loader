package io.averkhogliad.tubeloader.retry

/**
 * The reason of an attempt that outlived the budget its caller gave it: whoever holds that budget
 * answers this marker for a ran-out attempt, and the driver hands it to the policy, so a timed-out
 * attempt is retried like any other failure.
 *
 * It is built inside the loop's block — the opposite of [RetryExhausted], which is built once the loop
 * is over. It must not extend [kotlin.coroutines.cancellation.CancellationException], because the
 * driver rethrows a cancellation before the policy ever sees it. It carries no cause: telling one
 * attempt's budget from the whole operation's deadline is the job of whoever bounds the attempt, not
 * of the engine.
 */
object AttemptTimedOut : Exception("the attempt outlived its per-attempt budget")
