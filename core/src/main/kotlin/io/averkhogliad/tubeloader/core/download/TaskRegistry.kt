package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

internal class TaskRegistry(private val taskIdGenerator: TaskIdGenerator, private val clock: Clock) {
    private val states = MutableStateFlow<Map<TaskId, DownloadState>>(emptyMap())

    fun allocate(startedAt: Instant): TaskId {
        repeat(MAX_TASK_ID_ATTEMPTS) {
            val candidate = taskIdGenerator.next()
            if (register(candidate, startedAt)) return candidate
        }
        error("TaskId generator produced an occupied id $MAX_TASK_ID_ATTEMPTS times in a row")
    }

    fun statesFor(taskId: TaskId): Flow<DownloadState> = states.mapNotNull { it[taskId] }

    fun forget(taskId: TaskId) {
        states.update { it - taskId }
    }

    fun transition(taskId: TaskId, status: DownloadStatus) {
        states.update { snapshot ->
            val current = snapshot[taskId] ?: return@update snapshot
            if (current.status.isTerminal) return@update snapshot
            snapshot + (
                taskId to current.copy(
                    status = status,
                    finishedAt = if (status.isTerminal) clock.now() else current.finishedAt,
                )
                )
        }
    }

    fun updateProgress(taskId: TaskId, progress: Progress) {
        states.update { snapshot ->
            val current = snapshot[taskId] ?: return@update snapshot
            if (current.status.isTerminal) return@update snapshot
            snapshot + (taskId to current.copy(progress = progress))
        }
    }

    private fun register(taskId: TaskId, startedAt: Instant): Boolean {
        val initial = DownloadState(taskId, DownloadStatus.Queued, startedAt = startedAt)
        while (true) {
            val current = states.value
            if (taskId in current) return false
            if (states.compareAndSet(current, current + (taskId to initial))) return true
        }
    }
}

private const val MAX_TASK_ID_ATTEMPTS = 16

private val DownloadStatus.isTerminal: Boolean
    get() = this is DownloadStatus.Completed || this is DownloadStatus.Cancelled || this is DownloadStatus.Failed
