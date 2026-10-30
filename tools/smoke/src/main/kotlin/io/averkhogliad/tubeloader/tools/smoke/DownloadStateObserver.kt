package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.averkhogliad.tubeloader.core.download.isTerminal
import io.averkhogliad.tubeloader.core.usecase.ObserveDownloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

internal data class DownloadEntry(val name: String, val targetName: String)

internal class DownloadStateObserver(
    private val observeDownloads: ObserveDownloads,
    private val dashboard: ProgressDashboard,
) {

    private val states = MutableStateFlow<List<DownloadState>>(emptyList())
    private val entries = ConcurrentHashMap<TaskId, DownloadEntry>()
    private val finished = mutableSetOf<String>()

    suspend fun observe() {
        observeDownloads.states().collect { current ->
            states.value = current
            current.forEach { render(it) }
        }
    }

    fun register(taskId: TaskId, entry: DownloadEntry) {
        entries[taskId] = entry
    }

    fun snapshot(): List<DownloadState> = states.value

    suspend fun awaitTerminal(taskId: TaskId): DownloadState {
        val terminal = states.first { current -> current.any { it.taskId == taskId && it.status.isTerminal } }
        return terminal.first { it.taskId == taskId }
    }

    suspend fun awaitFirstTask(): DownloadState = states.first { current -> current.isNotEmpty() }.first()

    private fun render(state: DownloadState) {
        val key = state.taskId.toString()
        val entry = entries[state.taskId]
        val name = entry?.name ?: key
        if (state.status.isTerminal) {
            if (finished.add(key)) {
                dashboard.complete(key, formatTerminal(state, name, entry?.targetName))
            }
        } else {
            dashboard.update(key, formatProgress(state, name).ifEmpty { "[$key] $name: ${label(state.status)}" })
        }
    }
}

private fun label(status: DownloadStatus): String =
    when (status) {
        DownloadStatus.Queued -> "queued"
        DownloadStatus.LoadingMeta -> "loading metadata"
        DownloadStatus.Downloading -> "downloading"
        DownloadStatus.Finalizing -> "finalizing"
        DownloadStatus.Cancelling -> "cancelling"
        else -> status.toString()
    }
