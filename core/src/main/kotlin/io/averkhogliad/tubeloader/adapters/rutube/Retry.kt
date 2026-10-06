package io.averkhogliad.tubeloader.adapters.rutube

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val MAX_ATTEMPTS = 5
private val NETWORK_BUDGET = 30.seconds
private val RETRY_PAUSE = 250.milliseconds

/**
 * Repeats a network call while the transport fails or [isRetryable] accepts its answer, and gives up
 * after [attempts] or once [budget] is spent.
 *
 * The budget is counted per call rather than per download: a refusal while reading the metadata must
 * not eat the allowance the segments need. Exhaustion comes back as a failure, carrying the last
 * transport error; cancellation is never swallowed.
 */
internal suspend fun <T> withRetry(
    attempts: Int = MAX_ATTEMPTS,
    budget: Duration = NETWORK_BUDGET,
    pause: Duration = RETRY_PAUSE,
    isRetryable: (T) -> Boolean = { false },
    block: suspend () -> T,
): Result<T> {
    val start = TimeSource.Monotonic.markNow()
    var attempt = 1
    while (true) {
        val outcome = attemptOnce(block, isRetryable)
        if (outcome.isSuccess) return outcome
        val spent = start.elapsedNow()
        if (attempt >= attempts || spent + pause > budget) {
            val reason = IOException("the source stayed unreachable after $attempt attempts in $spent")
            return Result.failure(reason.apply { addSuppressed(outcome.exceptionOrNull() ?: reason) })
        }
        delay(pause)
        // cancellation has to break the wait here, not survive it into the next attempt
        currentCoroutineContext().ensureActive()
        attempt += 1
    }
}

private suspend fun <T> attemptOnce(
    block: suspend () -> T,
    isRetryable: (T) -> Boolean,
): Result<T> =
    try {
        val value = block()
        if (isRetryable(value)) Result.failure(IOException("the source answered a server error")) else Result.success(value)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: IOException) {
        Result.failure(failure)
    }
