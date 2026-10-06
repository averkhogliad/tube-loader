package io.averkhogliad.tubeloader.core.domain

sealed interface SourceProgress {

    data object Indeterminate : SourceProgress

    data class Absolute(val processed: Long, val total: Long) : SourceProgress

    data class Fraction(val ratio: Double) : SourceProgress
}
