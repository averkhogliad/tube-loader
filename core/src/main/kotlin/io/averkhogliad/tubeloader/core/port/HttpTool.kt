package io.averkhogliad.tubeloader.core.port

import java.io.Closeable
import java.io.InputStream

/**
 * Core port for reading content over HTTP. An adapter never picks a client library: it asks this
 * port to open a resource and copies the bytes itself.
 *
 * Closing is for what outlives a coroutine — the connection pool. It is idempotent and bounded; the
 * owner of the port calls it after the work has stopped.
 */
interface HttpTool : Closeable {
    /**
     * Opens [url] for reading. [headers] carries what the source requires (for example a referer).
     * The returned response is owned by the caller and must be closed by it.
     *
     * A transport failure (DNS, TCP, TLS, timeout) comes back as a failure; everything the server
     * answered, 4xx and 5xx included, comes back as a success carrying its status. A body that breaks
     * while it is read throws out of the read, because the block form hands the adapter a stream.
     */
    suspend fun open(url: String, headers: Map<String, String> = emptyMap()): Result<HttpResponse>
}

/**
 * The headers of a response: one value per name, the name kept as the server spelled it, and a name the
 * server repeated keeping its first value. A lookup by name ignores case, because HTTP/2 lowercases
 * every name and an adapter asking for `Retry-After` cannot know the spelling of the server.
 */
class Headers(private val lookup: Map<String, String>) : Map<String, String> by lookup {

    override fun get(key: String): String? =
        lookup.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let(lookup::get)

    override fun containsKey(key: String): Boolean = lookup.keys.any { it.equals(key, ignoreCase = true) }

    override fun equals(other: Any?): Boolean = lookup == other

    override fun hashCode(): Int = lookup.hashCode()

    override fun toString(): String = lookup.toString()
}

/**
 * A response opened by [HttpTool]: [status], [contentLength] and the body. [status] is what lets an
 * adapter tell a missing resource from a broken one without a client library.
 *
 * Closing belongs to the response, not to the stream: [content] with a block closes what it handed
 * out, and a response a caller turns down is closed by the caller itself.
 */
interface HttpResponse : Closeable {

    val status: Int

    val contentLength: Long?

    /**
     * The headers of the response, one value per name; a lookup by name ignores case, so the spelling of
     * the server does not matter. A name the server repeated keeps its first value.
     */
    val headers: Headers

    /**
     * The raw stream, closed by the caller. Meant for the rare read that needs the descriptor itself.
     */
    fun content(): InputStream

    /**
     * The default read: [block] runs on the stream and the stream is closed afterwards.
     */
    suspend fun <R> content(block: (InputStream) -> R): R
}
