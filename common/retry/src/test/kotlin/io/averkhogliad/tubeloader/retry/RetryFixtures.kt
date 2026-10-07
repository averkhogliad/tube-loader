package io.averkhogliad.tubeloader.retry

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val BASE_PAUSE = 250.milliseconds

internal const val UNREACHABLE = "the source stayed unreachable"

/**
 * A block that fails the first [failures] calls with a transport error and answers [value] after that.
 */
internal class Attempts(private val failures: Int) {

    private val used = AtomicInteger()

    val calls: Int get() = used.get()

    fun <T> answer(value: T): Result<T> {
        used.incrementAndGet()
        return if (calls <= failures) Result.failure(IOException(UNREACHABLE)) else Result.success(value)
    }
}

/**
 * A block whose own [withTimeout] fires on the first [timeouts] calls and answers [value] after that.
 *
 * The timeout is produced by the real [withTimeout] rather than constructed: kotlinx-coroutines keeps
 * the constructor of `TimeoutCancellationException` internal, so there is no way to build one by hand.
 */
internal class TimedOutAttempts(private val timeouts: Int) {

    private val used = AtomicInteger()

    val calls: Int get() = used.get()

    suspend fun <T> answer(value: T, budget: Duration = 1.milliseconds): Result<T> {
        used.incrementAndGet()
        if (calls <= timeouts) withTimeout(budget) { awaitCancellation() }
        return Result.success(value)
    }
}

/**
 * A stage that keeps what it was told and leaves the decision to [decide].
 */
internal class RecordingPolicy(private val answer: (FailedAttempt) -> RetryInstruction) : Stage {

    val seen = mutableListOf<FailedAttempt>()

    override fun decide(attempt: FailedAttempt): RetryInstruction {
        seen += attempt
        return answer(attempt)
    }
}

internal fun failedAttempt(
    number: Int,
    previousDelay: Duration = Duration.ZERO,
    cumulativeDelay: Duration = Duration.ZERO,
    elapsed: Duration = Duration.ZERO,
    failure: Throwable = IOException(UNREACHABLE),
): FailedAttempt = FailedAttempt(failure, number, previousDelay, cumulativeDelay, elapsed)
