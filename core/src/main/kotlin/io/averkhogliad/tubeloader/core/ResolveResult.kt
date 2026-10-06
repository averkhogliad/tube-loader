package io.averkhogliad.tubeloader.core

import io.averkhogliad.tubeloader.core.domain.MediaRef

sealed interface ResolveResult {

    data class Resolved(val ref: MediaRef) : ResolveResult

    data object Unsupported : ResolveResult

    data object NotFound : ResolveResult
}
