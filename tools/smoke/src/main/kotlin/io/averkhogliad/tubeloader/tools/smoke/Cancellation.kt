package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.download.isTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

internal fun CoroutineScope.launchCancelJobs(session: SmokeSession, cancelAfter: Duration?): Job =
    launch {
        val wait = cancelAfter ?: return@launch
        session.observer.awaitFirstTask()
        delay(wait)
        cancelRunning(session, "cancel-after $wait")
    }

internal fun cancelRunning(session: SmokeSession, reason: String) {
    var cancelled = 0
    session.observer.snapshot().forEach { state ->
        if (!state.status.isTerminal && session.useCases.cancelDownload.cancel(state.taskId)) {
            cancelled++
        }
    }
    if (cancelled > 0) {
        session.dashboard.note("$reason: cancelled $cancelled download(s)")
    }
}
