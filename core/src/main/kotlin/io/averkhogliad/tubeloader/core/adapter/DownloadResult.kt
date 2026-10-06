package io.averkhogliad.tubeloader.core.adapter

import io.averkhogliad.tubeloader.core.domain.DownloadError

sealed interface DownloadResult {

    data object Success : DownloadResult

    data class Failed(val error: DownloadError) : DownloadResult
}
