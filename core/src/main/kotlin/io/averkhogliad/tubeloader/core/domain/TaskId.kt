package io.averkhogliad.tubeloader.core.domain

@JvmInline
value class TaskId(val value: Int) {
    override fun toString(): String = "%08x".format(value)
}
