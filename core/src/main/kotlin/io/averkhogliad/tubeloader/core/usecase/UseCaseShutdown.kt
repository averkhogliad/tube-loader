package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.download.DownloadQueue
import kotlin.time.Duration

class UseCaseShutdown(private val queue: DownloadQueue) {

    suspend fun shutdown(timeout: Duration): Boolean = queue.shutdown(timeout)
}
