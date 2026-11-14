package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.isTerminal
import io.averkhogliad.tubeloader.core.usecase.DownloadSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/** Follows every download of the run and keeps its row of the live table up to date. */
internal class DownloadStateObserver(
    private val downloadSession: DownloadSession,
    private val dashboard: ProgressDashboard,
) {

    private val states = MutableStateFlow<List<DownloadState>>(emptyList())
    private val rows = ConcurrentHashMap<TaskId, TargetRow>()

    suspend fun observe() {
        downloadSession.states().collect { current ->
            states.value = current
            current.forEach { render(it) }
        }
    }

    fun register(taskId: TaskId, row: TargetRow) {
        rows[taskId] = row
    }

    fun snapshot(): List<DownloadState> = states.value

    suspend fun awaitTerminal(taskId: TaskId): DownloadState {
        val terminal = states.first { current -> current.any { it.taskId == taskId && it.status.isTerminal } }
        return terminal.first { it.taskId == taskId }
    }

    suspend fun awaitFirstTask(): DownloadState = states.first { current -> current.isNotEmpty() }.first()

    /**
     * A terminal status is written into the row of its own target as any other: the table is the
     * report of the run, so nothing leaves it for a line above.
     */
    private fun render(state: DownloadState) {
        val row = rows[state.taskId] ?: return
        dashboard.update(row.key, formatRow(state, row))
    }
}
