package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val CANCEL_POLL_INTERVAL = 200.milliseconds

internal fun CoroutineScope.launchCancelJobs(
    session: SmokeSession,
    cancelAfter: Duration?,
    cancelTaskId: TaskId?,
    workDone: AtomicBoolean,
): Job =
    launch {
        val after =
            cancelAfter?.let { wait ->
                launch {
                    session.observer.awaitFirstTask()
                    delay(wait)
                    cancelRunning(session, "cancel-after $wait")
                }
            }
        val byId =
            cancelTaskId?.let { taskId ->
                launch {
                    cancelWhenTracked(session, taskId, workDone)
                }
            }
        listOfNotNull(after, byId).forEach { it.join() }
    }

internal fun cancelRunning(session: SmokeSession, reason: String) {
    val terminal = mutableSetOf<TaskId>()
    session.observer.snapshot().forEach { state ->
        if (state.status.isTerminal) {
            terminal += state.taskId
        }
    }
    var cancelled = 0
    session.useCases.tracker.handles.value.forEach { handle ->
        if (handle.taskId !in terminal && session.useCases.cancelDownload.cancel(handle.taskId)) {
            cancelled++
        }
    }
    if (cancelled > 0) {
        session.dashboard.note("$reason: cancelled $cancelled download(s)")
    }
}

internal fun registerCancelOnShutdown(session: SmokeSession): Thread {
    val hook = Thread { cancelRunning(session, "shutdown requested") }
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
}

private suspend fun cancelWhenTracked(session: SmokeSession, taskId: TaskId, workDone: AtomicBoolean) {
    while (!workDone.get()) {
        if (session.useCases.cancelDownload.cancel(taskId)) {
            session.dashboard.note("cancelled task $taskId")
            return
        }
        delay(CANCEL_POLL_INTERVAL)
    }
    session.dashboard.note("task $taskId was never tracked")
}
