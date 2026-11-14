package io.averkhogliad.tubeloader.config

interface Config {

    val keys: Set<String>

    fun getOrNull(path: String): String?

    fun getTableOrNull(path: String): Map<String, Any>?

    /**
     * The leaf as the source produced it: a typed value for a parsed config, the text itself for a
     * source that keeps only strings. [getOrNull] stays the string projection of the same leaf for
     * the callers that predate the typed readers.
     */
    fun valueOrNull(path: String): Any? = getOrNull(path)

    fun <R> getOrNull(path: String, transform: (String) -> R): R? = getOrNull(path)?.let(transform)

    fun <R> getTableOrNull(path: String, transform: (Map<String, Any>) -> R): R? = getTableOrNull(path)?.let(transform)
}
