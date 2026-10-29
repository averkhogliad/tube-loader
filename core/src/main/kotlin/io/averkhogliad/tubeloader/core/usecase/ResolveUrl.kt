package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.domain.Source
import io.averkhogliad.tubeloader.core.domain.SourceId
import io.averkhogliad.tubeloader.core.facade.CoreFacade

class ResolveUrl(private val facade: CoreFacade) {

    val sources: List<Source> get() = facade.availableSources

    suspend fun byUrl(input: String): ResolveResult = facade.findByUrl(input)

    suspend fun byId(sourceId: SourceId, id: String): ResolveResult = facade.findById(sourceId, id)
}
