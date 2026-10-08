package io.averkhogliad.tubeloader.core.domain

sealed interface SourceProgress {

    data object Indeterminate : SourceProgress

    data class Absolute(val processed: Long, val total: Long) : SourceProgress

    data class Fraction(val ratio: Double) : SourceProgress

    /**
     * The share of the work done, or null when it cannot be told: an [Indeterminate] stage, or a value
     * outside its own measure. This is the one place the rules of a usable share live.
     */
    fun fraction(): Double? =
        when (this) {
            Indeterminate -> null
            is Absolute -> if (total > 0 && processed in 0..total) processed.toDouble() / total else null
            is Fraction -> ratio.takeIf { it.isFinite() && it in 0.0..1.0 }
        }
}
