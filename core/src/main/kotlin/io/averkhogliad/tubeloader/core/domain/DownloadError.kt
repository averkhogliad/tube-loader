package io.averkhogliad.tubeloader.core.domain

sealed interface DownloadError {

    data object NotFound : DownloadError

    data object NetworkTransient : DownloadError

    data object UrlExpired : DownloadError

    data object ExtractorBroken : DownloadError
}
