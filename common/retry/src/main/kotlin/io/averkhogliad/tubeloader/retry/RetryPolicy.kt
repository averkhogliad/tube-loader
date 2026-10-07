package io.averkhogliad.tubeloader.retry

import kotlin.time.Duration

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

/**
 * A stage that caps how much time a retry may spend. The cap is read by the chain resolver, which is
 * the only place that knows the pause the attempt is about to take.
 */
internal interface TimeBudget : Stage {

    val budget: Duration
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
 *
 * A time budget is judged here rather than by its own stage, because the question it asks is about
 * the pause the attempt is about to take: a pause that would carry the retry past the budget is not
 * taken at all.
 */
internal fun RetryPolicy.decide(attempt: FailedAttempt): RetryInstruction {
    val stages = stages()
    val decisions = stages.map { it.decide(attempt) }
    val awaited = decisions.filterIsInstance<RetryAfter>().maxByOrNull { it.delay }
    val pause = awaited?.delay ?: Duration.ZERO
    val budget = stages.filterIsInstance<TimeBudget>().minOfOrNull { it.budget }
    val overBudget = budget != null && attempt.elapsed + pause > budget
    return when {
        decisions.any { it is StopRetrying } || overBudget -> StopRetrying
        else -> awaited ?: ContinueRetrying
    }
}
