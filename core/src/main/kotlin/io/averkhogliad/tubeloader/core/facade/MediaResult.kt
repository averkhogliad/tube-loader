package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.MediaRef

/**
 * The outcome of resolving a media item: the link to it together with its metadata, or the reason
 * there is none.
 */
sealed interface MediaResult {

    data class Found(val ref: MediaRef, val meta: MediaMeta) : MediaResult

    data object NotFound : MediaResult

    data object Unsupported : MediaResult

    data class Failed(val error: DownloadError) : MediaResult
}
