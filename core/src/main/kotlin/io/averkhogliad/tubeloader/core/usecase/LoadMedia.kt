package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.facade.CoreFacade

class LoadMedia(private val facade: CoreFacade) {

    suspend fun load(ref: MediaRef): LoadMetaResult = facade.loadMeta(ref)
}
