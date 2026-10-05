package io.averkhogliad.tubeloader.core

@JvmInline
value class SourceId(val index: Int)

data class Source(val id: SourceId, val displayName: String)
