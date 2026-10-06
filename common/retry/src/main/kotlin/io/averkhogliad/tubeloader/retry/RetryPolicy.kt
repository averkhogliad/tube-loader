package io.averkhogliad.tubeloader.retry

/**
 * Decides whether a failed attempt is worth repeating and how long to wait first.
 *
 * A policy is a chain of [Stage]s; the companion is the empty element the chain starts from, so
 * `RetryPolicy.stopAtAttempts(5)` reads as the first link. A stage is a pure function of the attempt
 * it is handed and holds no state, so the same policy can serve any number of concurrent retries.
 */
interface RetryPolicy {

    companion object : RetryPolicy
}

/**
 * One link of the chain: what a single factory contributes to every attempt.
 */
interface Stage : RetryPolicy {

    fun decide(attempt: FailedAttempt): RetryInstruction
}

private class Combined(val outer: RetryPolicy, val inner: RetryPolicy) : RetryPolicy

/**
 * Appends [next] to the chain. Appending the empty element answers the same policy back, so an
 * optional link can be added without a branch at the call site.
 */
fun RetryPolicy.then(next: RetryPolicy): RetryPolicy = if (next === RetryPolicy) this else Combined(this, next)

/**
 * Chains two policies: a stop on either side stops the retry, and the pause is the longer of the two.
 */
operator fun RetryPolicy.plus(other: RetryPolicy): RetryPolicy = then(other)

internal fun RetryPolicy.stages(): List<Stage> =
    when {
        this is Stage -> listOf(this)
        this is Combined -> outer.stages() + inner.stages()
        else -> emptyList()
    }

/**
 * Folds the decisions of the chain into the single instruction the driver acts on. Nothing votes for
 * a pause, the retry repeats at once; nothing stops it, the retry continues.
 */
internal fun RetryPolicy.decide(attempt: FailedAttempt): RetryInstruction {
    val decisions = stages().map { it.decide(attempt) }
    if (decisions.any { it is StopRetrying }) return StopRetrying
    return decisions.filterIsInstance<RetryAfter>().maxByOrNull { it.delay } ?: ContinueRetrying
}
