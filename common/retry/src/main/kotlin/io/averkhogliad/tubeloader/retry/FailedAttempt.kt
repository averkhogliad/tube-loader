package io.averkhogliad.tubeloader.retry

import kotlin.time.Duration

/**
 * What a policy is told about the attempt that just failed.
 *
 * [previousDelay] lets a policy look back at the pause it asked for last time — the input an
 * exponential backoff needs; [cumulativeDelay] is the sum of the pauses spent so far; [elapsed] is
 * the time since the retry started, pauses and attempts alike, and it is the one a time budget
 * bounds.
 */
data class FailedAttempt(
    val failure: Throwable,
    val number: Int,
    val previousDelay: Duration,
    val cumulativeDelay: Duration,
    val elapsed: Duration,
)
