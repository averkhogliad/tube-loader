package io.averkhogliad.tubeloader.infra.binaries

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * The network seam of the distribution: the mechanism owns checksums, unpacking and installation,
 * while [download] only puts the body of the source on the disk and [body] only reads one.
 */
interface BinaryFetch {

    suspend fun download(tool: String, source: URI, target: Path, followRedirects: Boolean)

    suspend fun body(tool: String, source: URI, followRedirects: Boolean): String
}

/**
 * The default fetch: one GET per call, no progress and no retries, as the cold start of a tool
 * deserves. A failure of the transport is an [IOException] of the call.
 */
internal class HttpBinaryFetch : BinaryFetch {

    override suspend fun download(tool: String, source: URI, target: Path, followRedirects: Boolean) {
        val client = client(followRedirects)
        val response = exchange(tool, client, request(source), HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() != HTTP_OK) {
            throw DownloadFailed(tool, source, null, response.statusCode())
        }
    }

    override suspend fun body(tool: String, source: URI, followRedirects: Boolean): String =
        exchange(tool, client(followRedirects), request(source), HttpResponse.BodyHandlers.ofString()).body()

    private suspend fun <T> exchange(
        tool: String,
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> =
        withContext(Dispatchers.IO) {
            try {
                client.send(request, handler)
            } catch (failure: IOException) {
                throw DownloadFailed(tool, request.uri(), failure)
            } catch (failure: InterruptedException) {
                // the call runs on a thread of its own; restoring the flag keeps the interrupt visible
                Thread.currentThread().interrupt()
                throw DownloadFailed(tool, request.uri(), failure)
            }
        }

    private fun client(followRedirects: Boolean): HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(if (followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
            .build()

    private fun request(source: URI): HttpRequest = HttpRequest.newBuilder(source).GET().build()

    private companion object {

        const val HTTP_OK = 200
    }
}
