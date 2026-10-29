package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.RandomTaskIdGenerator
import io.averkhogliad.tubeloader.core.download.TaskIdGenerator
import java.util.concurrent.atomic.AtomicBoolean

private class SeededTaskIdGenerator(private val seeded: TaskId) : TaskIdGenerator {

    private val used = AtomicBoolean(false)

    override fun next(): TaskId {
        if (used.compareAndSet(false, true)) {
            return seeded
        }
        return RandomTaskIdGenerator.next()
    }
}

internal fun taskIdGenerator(cancelTaskId: TaskId?): TaskIdGenerator =
    cancelTaskId?.let { SeededTaskIdGenerator(it) } ?: RandomTaskIdGenerator
