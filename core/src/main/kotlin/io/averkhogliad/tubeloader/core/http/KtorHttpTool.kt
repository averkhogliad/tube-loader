package io.averkhogliad.tubeloader.core.http

import io.averkhogliad.tubeloader.core.port.Headers
import io.averkhogliad.tubeloader.core.port.HttpResponse
import io.averkhogliad.tubeloader.core.port.HttpTool
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/**
 * The port over Ktor. [client] is built, configured and closed by the caller, which installs
 * `HttpTimeout` from its own `HttpToolConfig` and registers shutdown; this class only borrows it, so
 * [close] releases nothing.
 */
class KtorHttpTool(private val client: HttpClient) : HttpTool {

    override suspend fun open(url: String, headers: Map<String, String>): Result<HttpResponse> =
        try {
            val response = client.request(url) { applyHeaders(headers) }
            Result.success(
                KtorHttpResponse(
                    status = response.status.value,
                    contentLength = response.contentLength(),
                    headers = Headers(response.headers.entries().associate { it.key to it.value.first() }),
                    body = response.bodyAsChannel(),
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            // a transport failure is an outcome of the port, never a throw (spec: HttpTool contract)
            Result.failure(failure)
        } catch (failure: UnresolvedAddressException) {
            // CIO reports a DNS failure as this, not as an IOException
            Result.failure(failure)
        }

    /**
     * Releases nothing: the client belongs to the caller.
     */
    override fun close() = Unit

    private fun HttpRequestBuilder.applyHeaders(adapterHeaders: Map<String, String>) {
        val overridden = adapterHeaders.keys
        DEFAULTS.forEach { (name, value) ->
            if (overridden.none { it.equals(name, ignoreCase = true) }) headers.append(name, value)
        }
        adapterHeaders.forEach { (name, value) -> headers.append(name, value) }
    }

    companion object {

        private val DEFAULTS =
            mapOf(
                HttpHeaders.UserAgent to "Tubeloader/0",
                HttpHeaders.Accept to "*/*",
                HttpHeaders.AcceptEncoding to "identity",
            )
    }
}

object HttpTools {
    fun create(client: HttpClient): HttpTool = KtorHttpTool(client)
}
