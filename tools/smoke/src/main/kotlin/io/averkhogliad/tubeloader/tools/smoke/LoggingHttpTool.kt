package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.port.HttpResponse
import io.averkhogliad.tubeloader.core.port.HttpTool

private const val HTTP_OK = 200
private const val HTTP_LAST_SUCCESS = 299
private val ACCEPTED_STATUSES = HTTP_OK..HTTP_LAST_SUCCESS

/**
 * Reports what the adapter discards: a transport failure becomes a bare `NetworkTransient` and a bad
 * status becomes `ExtractorBroken`, neither naming a cause nor a status code. The reason is recorded
 * here, at the composition root, through the public port — so the detail is observable without
 * widening the `:core` contract.
 */
internal class LoggingHttpTool(private val delegate: HttpTool, private val log: (String) -> Unit) : HttpTool {

    override suspend fun open(url: String, headers: Map<String, String>): Result<HttpResponse> {
        val opened = delegate.open(url, headers)
        opened.onSuccess { response ->
            if (response.status !in ACCEPTED_STATUSES) log("http ${response.status}: $url")
        }
        opened.onFailure { failure -> log("http failed (${failure.javaClass.simpleName}): $url: ${failure.message}") }
        return opened
    }

    override fun close() = delegate.close()
}
