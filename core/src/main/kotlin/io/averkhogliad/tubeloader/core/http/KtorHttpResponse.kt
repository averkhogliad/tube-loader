package io.averkhogliad.tubeloader.core.http

import io.averkhogliad.tubeloader.core.port.Headers
import io.averkhogliad.tubeloader.core.port.HttpResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStream

internal class KtorHttpResponse(
    override val status: Int,
    override val contentLength: Long?,
    override val headers: Headers,
    private val body: ByteReadChannel,
) : HttpResponse {

    override fun content(): InputStream = body.toInputStream()

    override suspend fun <R> content(block: (InputStream) -> R): R = content().use(block)

    override fun close() {
        body.cancel(null)
    }
}
