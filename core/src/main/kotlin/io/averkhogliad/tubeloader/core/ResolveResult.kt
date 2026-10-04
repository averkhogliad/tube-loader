package io.averkhogliad.tubeloader.core

sealed interface ResolveResult {
    data class Resolved(val ref: MediaRef) : ResolveResult

    data object Unsupported : ResolveResult

    data object NotFound : ResolveResult
}
