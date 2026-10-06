package io.averkhogliad.tubeloader.retry

import kotlin.time.Duration

/**
 * What the driver is told to do with the failure it was handed.
 */
sealed interface RetryInstruction

/**
 * The failure is final: the driver answers it to the caller.
 */
object StopRetrying : RetryInstruction

/**
 * Another attempt is worth making, and there is no reason to wait before it.
 */
object ContinueRetrying : RetryInstruction

/**
 * Another attempt is worth making after [delay].
 */
data class RetryAfter(val delay: Duration) : RetryInstruction
