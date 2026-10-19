package io.averkhogliad.tubeloader.core.domain

/**
 * How far a download has got: [Indeterminate] when it cannot be told, [Absolute] for a countable
 * step (bytes, segments) and [Fraction] for a share the source computed itself.
 */
sealed interface Progress {

    data object Indeterminate : Progress

    data class Absolute(val processed: Long, val total: Long) : Progress

    data class Fraction(val ratio: Double) : Progress
}

/**
 * The share of the work done, or null when it cannot be told: an [Progress.Indeterminate] stage, or a
 * value outside its own measure. This is the one place the rules of a usable share live.
 */
fun Progress.fraction(): Double? =
    when (this) {
        Progress.Indeterminate -> {
            null
        }

        is Progress.Absolute -> {
            if (total > 0 && processed in 0..total) processed.toDouble() / total else null
        }

        is Progress.Fraction -> {
            ratio.takeIf { it.isFinite() && it in 0.0..1.0 }
        }
    }
