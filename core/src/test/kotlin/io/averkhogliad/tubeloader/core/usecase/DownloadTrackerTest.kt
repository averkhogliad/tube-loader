package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadHandle
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

private val queuedAt: Instant = Instant.parse("2026-09-30T10:00:00Z")

private fun handleFor(taskId: TaskId): DownloadHandle {
    val state = MutableStateFlow(DownloadState(taskId, DownloadStatus.Queued, startedAt = queuedAt))
    return DownloadHandle(taskId, state, {})
}

class DownloadTrackerTest :
    FreeSpec({

        "register" - {
            "keeps the registered handles in registration order" {
                // given
                val tracker = DownloadTracker()
                val first = handleFor(TaskId(1))
                val second = handleFor(TaskId(2))

                // when
                tracker.register(first)
                tracker.register(second)

                // then
                tracker.handles.first() shouldBe listOf(first, second)
            }

            "keeps one handle per task id" {
                // given
                val tracker = DownloadTracker()
                val taskId = TaskId(7)
                val first = handleFor(taskId)
                val second = handleFor(taskId)

                // when
                tracker.register(first)
                tracker.register(second)

                // then
                tracker.handles.first() shouldBe listOf(second)
            }
        }

        "handle" - {
            "returns the handle registered under the task id" {
                // given
                val tracker = DownloadTracker()
                val handle = handleFor(TaskId(3))

                // when
                tracker.register(handle)

                // then
                tracker.handle(handle.taskId) shouldBeSameInstanceAs handle
            }

            "returns no handle for a task id that was never registered" {
                // given
                val tracker = DownloadTracker()
                val unknown = TaskId(Arb.int().next())

                // when
                val handle = tracker.handle(unknown)

                // then
                handle shouldBe null
            }
        }
    })
