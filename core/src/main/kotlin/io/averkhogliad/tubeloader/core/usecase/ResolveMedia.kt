package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.domain.Source
import io.averkhogliad.tubeloader.core.facade.CoreFacade
import io.averkhogliad.tubeloader.core.facade.MediaResult

/**
 * The resolution of a media item: by url, or by the code of a source and a media id.
 *
 * The link to the media and its metadata come in one answer, so the caller does not combine two
 * results of its own.
 */
class ResolveMedia(private val facade: CoreFacade) {

    val sources: List<Source> get() = facade.availableSources

    suspend fun byUrl(input: String): MediaResult = facade.findByUrl(input)

    suspend fun byId(code: String, mediaId: String): MediaResult = facade.findById(code, mediaId)
}
