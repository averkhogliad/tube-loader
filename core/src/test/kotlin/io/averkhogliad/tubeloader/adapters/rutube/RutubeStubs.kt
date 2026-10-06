package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.HttpStub
import io.averkhogliad.tubeloader.core.port.textBody
import java.io.IOException
import java.net.UnknownHostException

internal const val RECORDING_OPTIONS = "rutube/playOptions-download.json"

internal fun expectedOptionsUrl(id: String) =
    "https://rutube.ru/api/play/options/$id/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"

/**
 * A response recorded from the source, served with the given status.
 */
internal fun recordedStub(name: String, status: Int = 200): HttpStub =
    HttpStub.Respond(textBody(FakeHttpTool.resourceText("rutube/$name"), status))

internal fun recordedStubWith(text: String, status: Int): HttpStub = HttpStub.Respond(textBody(text, status))

internal val SERVER_ERROR_STUB: HttpStub = HttpStub.Respond(textBody("unavailable", status = 503))

internal val CLIENT_ERROR_STUB: HttpStub = HttpStub.Respond(textBody("gone", status = 404))

internal val TRANSPORT_FAILURE_STUB: HttpStub = HttpStub.Fail(UnknownHostException("rutube.ru"))

internal val CONNECTION_RESET_STUB: HttpStub = HttpStub.Fail(IOException("reset"))
