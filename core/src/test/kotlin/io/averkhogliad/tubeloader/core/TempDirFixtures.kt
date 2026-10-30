package io.averkhogliad.tubeloader.core

import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.milliseconds

private val retryPauses = listOf(20, 40, 80, 160).map { it.milliseconds }

/**
 * Wipes a directory, retrying while the deletion is refused.
 *
 * On Windows an open handle of a file that was deleted keeps the record in delete-pending: the
 * deletion fails until the handle is closed. A genuine leftover has to fail the build, so the last
 * failure is rethrown instead of being swallowed.
 */
@OptIn(ExperimentalPathApi::class)
internal suspend fun deleteWithRetry(dir: Path) {
    var lastFailure: Exception? = null
    repeat(retryPauses.size + 1) { attempt ->
        try {
            dir.deleteRecursively()
            return
        } catch (failure: Exception) {
            lastFailure = failure
            retryPauses.getOrNull(attempt)?.let { delay(it) }
        }
    }
    throw lastFailure!!
}
