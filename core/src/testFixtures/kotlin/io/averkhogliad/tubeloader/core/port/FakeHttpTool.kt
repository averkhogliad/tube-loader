package io.averkhogliad.tubeloader.core.port

import java.io.ByteArrayInputStream

data class OpenCall(val url: String, val headers: Map<String, String>)

private const val HTTP_OK = 200

/**
 * One prepared answer of a stubbed route: either a response or a transport failure.
 */
sealed interface HttpStub {

    data class Respond(val body: HttpResponse) : HttpStub

    data class Fail(val error: Throwable) : HttpStub
}

fun httpBody(content: ByteArray, contentLength: Long? = content.size.toLong(), status: Int = HTTP_OK): HttpResponse =
    HttpResponse(status, ByteArrayInputStream(content), contentLength)

fun textBody(content: String, status: Int = HTTP_OK): HttpResponse = httpBody(content.toByteArray(), status = status)

/**
 * Stub of [HttpTool] for adapter tests: every call is recorded in [opened], and answers come from a
 * prepared route. A route is matched by url prefix, its stubs are consumed in order, the last one
 * repeating — so "two failures then a success" is expressed as three stubs.
 */
class FakeHttpTool : HttpTool {

    val opened = mutableListOf<OpenCall>()

    private val routes = mutableListOf<Route>()

    private var fallback: suspend (String) -> HttpResponse = { httpBody(ByteArray(0)) }

    override fun close() = Unit

    override suspend fun open(url: String, headers: Map<String, String>): HttpResponse {
        opened += OpenCall(url, headers)
        val route = routes.firstOrNull { url.startsWith(it.prefix) } ?: return fallback(url)
        return route.next(url)
    }

    /**
     * Answers every request with [content].
     */
    fun respondWith(
        content: ByteArray,
        contentLength: Long? = content.size.toLong(),
        status: Int = HTTP_OK,
    ): FakeHttpTool = always(HttpStub.Respond(httpBody(content, contentLength, status)))

    /**
     * Answers every request with the classpath resource at [resource].
     */
    fun recording(resource: String, status: Int = HTTP_OK): FakeHttpTool =
        always(HttpStub.Respond(textBody(resourceText(resource), status)))

    /**
     * Answers every request with [body].
     */
    fun respondingWith(body: HttpResponse): FakeHttpTool = always(HttpStub.Respond(body))

    /**
     * Fails every request with [error].
     */
    fun failingWith(error: Throwable): FakeHttpTool = always(HttpStub.Fail(error))

    /**
     * Answers every request with the prepared [stubs]; the last one repeats.
     */
    fun always(vararg stubs: HttpStub): FakeHttpTool {
        fallback = stubReader(stubs.copyOf())
        return this
    }

    /**
     * Answers requests whose url starts with [prefix] from [stubs]; the last one repeats. Replaces a
     * route with the same prefix.
     */
    fun route(prefix: String, vararg stubs: HttpStub): FakeHttpTool {
        routes.removeAll { it.prefix == prefix }
        routes += Route(prefix, stubs.asList())
        return this
    }

    /**
     * Answers requests whose url equals [url] with [content].
     */
    fun route(url: String, content: ByteArray, status: Int = HTTP_OK): FakeHttpTool =
        route(url, HttpStub.Respond(httpBody(content, status = status)))

    /**
     * Answers requests whose url equals [url] with the classpath resource at [resource].
     */
    fun routeRecording(url: String, resource: String, status: Int = HTTP_OK): FakeHttpTool =
        route(url, HttpStub.Respond(textBody(resourceText(resource), status)))

    private class Route(val prefix: String, private val stubs: List<HttpStub>) {

        private var index = 0

        suspend fun next(url: String): HttpResponse {
            val stub = stubs[minOf(index, stubs.lastIndex)]
            index += 1
            return serve(url, stub)
        }
    }

    private fun stubReader(stubs: Array<out HttpStub>): suspend (String) -> HttpResponse {
        var index = 0
        return { url ->
            val stub = stubs[minOf(index, stubs.lastIndex)]
            index += 1
            serve(url, stub)
        }
    }

    companion object {
        private suspend fun serve(url: String, stub: HttpStub): HttpResponse =
            when (stub) {
                is HttpStub.Respond -> stub.body
                is HttpStub.Fail -> throw stub.error
            }

        fun resourceText(resource: String): String =
            FakeHttpTool::class.java.classLoader
                ?.getResourceAsStream(resource)
                ?.use { it.readBytes() }
                ?.decodeToString()
                ?: throw IllegalArgumentException("missing fixture on the classpath: $resource")
    }
}
