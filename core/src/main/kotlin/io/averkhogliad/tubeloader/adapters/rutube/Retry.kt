package io.averkhogliad.tubeloader.adapters.rutube

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAX_ATTEMPTS = 5
private const val UNREACHABLE = "the source stayed unreachable"
private val NETWORK_BUDGET = 30.seconds
private val RETRY_PAUSE = 250.milliseconds

/**
 * Repeats a network call while the transport fails or [isRetryable] accepts its answer, and gives up
 * after [attempts] or once [budget] is spent.
 *
 * The budget is counted per call rather than per download: a refusal while reading the metadata must
 * not eat the allowance the segments need. Exhaustion comes back as a failure, carrying the last
 * transport error; cancellation is never swallowed.
 *
 * A value [isRetryable] turns down is handed to [dispose]: the attempt that opened it is over, and
 * nothing else will close what it holds.
 */
internal suspend fun <T> withRetry(
    attempts: Int = MAX_ATTEMPTS,
    budget: Duration = NETWORK_BUDGET,
    isRetryable: (T) -> Boolean = { false },
    dispose: (T) -> Unit = {},
    block: suspend () -> T,
): Result<T> {
    val start = TimeSource.Monotonic.markNow()
    var attempt = 1
    while (true) {
        val outcome = attemptOnce(block, isRetryable, dispose)
        if (outcome.isSuccess) return outcome
        val spent = start.elapsedNow()
        if (attempt >= attempts || spent + RETRY_PAUSE > budget) {
            val reason = IOException("$UNREACHABLE after $attempt attempts in $spent")
            return Result.failure(reason.apply { addSuppressed(outcome.exceptionOrNull() ?: reason) })
        }
        delay(RETRY_PAUSE)
        // cancellation has to break the wait here, not survive it into the next attempt
        currentCoroutineContext().ensureActive()
        attempt += 1
    }
}

private suspend fun <T> attemptOnce(
    block: suspend () -> T,
    isRetryable: (T) -> Boolean,
    dispose: (T) -> Unit,
): Result<T> =
    try {
        val value = block()
        if (isRetryable(value)) {
            dispose(value)
            Result.failure(IOException(UNREACHABLE))
        } else {
            Result.success(value)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: IOException) {
        Result.failure(failure)
    }
