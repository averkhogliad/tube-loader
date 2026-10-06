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
 */
fun RetryPolicy.continueIf(predicate: (Throwable) -> Boolean): RetryPolicy = then(ContinueIf(predicate))

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
 * Appends a stage that stops once the pauses already spent reach [budget].
 *
 * The budget covers the pauses, not the time the attempts themselves take: a policy sees only what
 * the driver reports, and the driver knows just how long it slept.
 */
fun RetryPolicy.withinBudget(budget: Duration): RetryPolicy = then(WithinBudget(budget))

private class StopAtAttempts(private val attempts: Int) : Stage {

    init {
        require(attempts >= 1) { "a retry needs at least one attempt, got $attempts" }
    }

    override fun decide(attempt: FailedAttempt): RetryInstruction =
        if (attempt.number >= attempts) StopRetrying else ContinueRetrying
}

private class ContinueIf(private val predicate: (Throwable) -> Boolean) : Stage {

    override fun decide(attempt: FailedAttempt): RetryInstruction =
        if (predicate(attempt.failure)) ContinueRetrying else StopRetrying
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

private class WithinBudget(private val budget: Duration) : Stage {

    override fun decide(attempt: FailedAttempt): RetryInstruction =
        if (attempt.cumulativeDelay >= budget) StopRetrying else ContinueRetrying
}
