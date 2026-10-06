package io.averkhogliad.tubeloader.core.domain

sealed interface Progress {

    data object Indeterminate : Progress

    data class Determinate(val current: Long, val total: Long) : Progress
}
