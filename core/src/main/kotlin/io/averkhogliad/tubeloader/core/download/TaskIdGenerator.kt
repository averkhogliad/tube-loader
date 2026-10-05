package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.TaskId
import kotlin.random.Random

fun interface TaskIdGenerator {
    fun next(): TaskId
}

object RandomTaskIdGenerator : TaskIdGenerator {
    override fun next(): TaskId = TaskId(Random.nextInt())
}
