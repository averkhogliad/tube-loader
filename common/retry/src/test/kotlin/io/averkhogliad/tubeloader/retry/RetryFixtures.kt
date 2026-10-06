package io.averkhogliad.tubeloader.retry

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
    failure: Throwable = IOException(UNREACHABLE),
): FailedAttempt = FailedAttempt(failure, number, previousDelay, cumulativeDelay)
