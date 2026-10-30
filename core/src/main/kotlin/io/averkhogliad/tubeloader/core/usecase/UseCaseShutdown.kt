package io.averkhogliad.tubeloader.core.usecase

import kotlin.time.Duration

class UseCaseShutdown(private val stop: suspend (Duration) -> Boolean) {

    suspend fun shutdown(timeout: Duration): Boolean = stop(timeout)
}
