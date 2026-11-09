package io.averkhogliad.tubeloader.core.adapter

/**
 * The registration of the sources: the code of a source is the key of its adapter.
 *
 * A code is unique among the sources: a repeated code is a build error rather than a silent
 * replacement of the adapter — the link `code:mediaId` would point at two different answers, and the
 * replaced adapter would leave no trace.
 */
fun adaptersByCode(registrations: List<Pair<String, SourceAdapter>>): Map<String, SourceAdapter> {
    val byCode = LinkedHashMap<String, SourceAdapter>()
    registrations.forEach { (code, adapter) ->
        require(code !in byCode) { "a source code must be unique: '$code'" }
        byCode[code] = adapter
    }
    return byCode
}
