package io.averkhogliad.tubeloader.config

interface Config {
    val keys: Set<String>

    fun getOrNull(path: String): String?

    fun getTableOrNull(path: String): Map<String, Any>?

    fun <R> getOrNull(path: String, transform: (String) -> R): R? = getOrNull(path)?.let(transform)

    fun <R> getTableOrNull(path: String, transform: (Map<String, Any>) -> R): R? = getTableOrNull(path)?.let(transform)
}
