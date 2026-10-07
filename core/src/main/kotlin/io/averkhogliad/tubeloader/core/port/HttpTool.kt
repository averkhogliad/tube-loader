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
     */
    suspend fun open(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/**
 * A response opened by [HttpTool]: its metadata and the body stream. [status] carries the HTTP status
 * code so an adapter can tell a missing resource from a broken one without a client library.
 *
 * Closing is the response's own operation, not the stream's: a caller that reads the body with
 * [bytes] or turns the response down never touches [body] itself.
 */
data class HttpResponse(
    val status: Int,
    val body: InputStream,
    val contentLength: Long? = null,
) : Closeable {

    override fun close() = body.close()
}

fun HttpResponse.bytes(): ByteArray = use { it.body.readBytes() }
