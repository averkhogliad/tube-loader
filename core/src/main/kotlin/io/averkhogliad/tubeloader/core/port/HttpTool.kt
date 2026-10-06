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
     * Opens the body of [url] for reading. [headers] carries what the source requires (for example
     * a referer). The returned body is owned by the caller and must be closed by it.
     */
    suspend fun open(url: String, headers: Map<String, String> = emptyMap()): HttpBody
}

/**
 * A response opened by [HttpTool]: its metadata and the body stream. [status] carries the HTTP status
 * code so an adapter can tell a missing resource from a broken one without a client library.
 */
data class HttpBody(
    val status: Int,
    val body: InputStream,
    val contentLength: Long? = null,
)

fun HttpBody.bytes(): ByteArray = body.use(InputStream::readBytes)
