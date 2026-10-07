package io.averkhogliad.tubeloader.retry

import kotlin.time.Duration

/**
 * How far the pause of [exponentialBackoff] may be doubled. Past it the shift would overflow into a
 * negative duration, so a long-lived retry keeps a finite pause instead of an immediate one.
 */
private const val MAX_BACKOFF_STEP = 30

/**
 * Appends a stage that stops once [attempts] attempts have been made.
 */
fun RetryPolicy.stopAtAttempts(attempts: Int): RetryPolicy = then(StopAtAttempts(attempts))

/**
 * Appends a stage that stops as soon as [predicate] turns a failure down.
 *
 * The predicate receives the [FailedAttempt] as a receiver, so it weighs the failure itself against the
 * metadata of the attempt that carried it — its number, the pauses spent so far and the elapsed time.
 *
 * ```
 * policy.continueIf { number < 3 && failure is IOException }
 * ```
 */
fun RetryPolicy.continueIf(predicate: FailedAttempt.() -> Boolean): RetryPolicy = then(ContinueIf(predicate))

/**
 * Appends a stage that waits the same [delay] before every attempt.
 */
fun RetryPolicy.constantDelay(delay: Duration): RetryPolicy = then(ConstantDelay(delay))

/**
 * Appends a stage that doubles the pause with every attempt, starting at [base] and never growing
 * past [limit].
 *
 * [randomizationFactor] spreads the pause over a window around it: a value of 0.1 keeps it within
 * ±10%, which keeps a fleet of callers from returning to a rate-limited source in lockstep. The
 * source of randomness is a parameter so that a test can pin it with a seeded generator and still
 * observe the window.
 */
fun RetryPolicy.exponentialBackoff(
    base: Duration,
    limit: Duration = Duration.INFINITE,
    randomizationFactor: Double = 0.0,
    random: () -> Double = Math::random,
): RetryPolicy {
    require(randomizationFactor in 0.0..1.0) {
        "a randomization factor must be within 0.0..1.0, got $randomizationFactor"
    }
    return then(ExponentialBackoff(base, limit, randomizationFactor, random))
}

/**
 * Appends a stage that stops once the retry has spent [budget] of elapsed time.
 *
 * The budget covers everything the retry has cost — the attempts themselves and the pauses between
 * them. A pause that would carry the retry past the budget is not taken at all, so the budget is a
 * ceiling rather than a checkpoint.
 */
fun RetryPolicy.withinBudget(budget: Duration): RetryPolicy = then(WithinBudget(budget))

private class StopAtAttempts(private val attempts: Int) : Stage {

    init {
        require(attempts >= 1) { "a retry needs at least one attempt, got $attempts" }
    }

    override fun decide(attempt: FailedAttempt): RetryInstruction =
        if (attempt.number >= attempts) StopRetrying else ContinueRetrying
}

private class ContinueIf(private val predicate: FailedAttempt.() -> Boolean) : Stage {

    override fun decide(attempt: FailedAttempt): RetryInstruction =
        if (attempt.predicate()) ContinueRetrying else StopRetrying
}

private class ConstantDelay(private val delay: Duration) : Stage {

    override fun decide(attempt: FailedAttempt): RetryInstruction = RetryAfter(delay)
}

private class ExponentialBackoff(
    private val base: Duration,
    private val limit: Duration,
    private val randomizationFactor: Double,
    private val random: () -> Double,
) : Stage {

    override fun decide(attempt: FailedAttempt): RetryInstruction {
        val step = (attempt.number - 1).coerceAtMost(MAX_BACKOFF_STEP)
        val pause = base * (1 shl step)
        val spread = pause * (randomizationFactor * (2 * random() - 1))
        return RetryAfter(minOf(limit, pause + spread))
    }
}

private class WithinBudget(override val budget: Duration) : TimeBudget {

    override fun decide(attempt: FailedAttempt): RetryInstruction = ContinueRetrying
}
