package io.averkhogliad.tubeloader.core.port

import io.averkhogliad.tubeloader.core.port.HttpBody
import io.averkhogliad.tubeloader.core.port.HttpTool
import java.io.ByteArrayInputStream
import java.io.InputStream

data class OpenCall(val url: String, val headers: Map<String, String>)

class FakeHttpTool : HttpTool {

    var onOpen: suspend (String, Map<String, String>) -> HttpBody = { _, _ ->
        HttpBody(contentLength = 0L, body = ByteArrayInputStream(ByteArray(0)))
    }

    val opened = mutableListOf<OpenCall>()

    override fun close() = Unit

    override suspend fun open(url: String, headers: Map<String, String>): HttpBody {
        opened += OpenCall(url, headers)
        return onOpen(url, headers)
    }

    fun respondWith(content: ByteArray, contentLength: Long? = content.size.toLong()) {
        onOpen = { _, _ -> HttpBody(contentLength, ByteArrayInputStream(content)) }
    }
}

fun HttpBody.bytes(): ByteArray = body.use(InputStream::readBytes)
