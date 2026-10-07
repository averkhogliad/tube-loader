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
     * A transport failure (DNS, TCP, TLS, timeout, a broken body) comes back as a failure; everything
     * the server answered, 4xx and 5xx included, comes back as a success carrying its status.
     */
    suspend fun open(url: String, headers: Map<String, String> = emptyMap()): Result<HttpResponse>
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
     * The raw stream, closed by the caller. Meant for the rare read that needs the descriptor itself.
     */
    fun content(): InputStream

    /**
     * The default read: [block] runs on the stream and the stream is closed afterwards.
     */
    suspend fun <R> content(block: (InputStream) -> R): R
}
