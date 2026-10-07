package io.averkhogliad.tubeloader.core.http

import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.port.HttpTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

private const val URL = "http://source.test/file"

private fun client(engine: MockEngine, config: HttpToolConfig = HttpToolConfig()): HttpClient =
    HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeout.inWholeMilliseconds
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        install(ContentEncoding) { gzip() }
        expectSuccess = false
        followRedirects = false
    }

private fun tool(engine: MockEngine): HttpTool = HttpTools.create(client(engine)) { HttpToolConfig() }

private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

class KtorHttpToolTest :
    FreeSpec({

        "open" - {
            "returns the body of a 200 as a success" {
                runTest {
                    // given
                    val engine = MockEngine { respond("payload", HttpStatusCode.OK) }

                    // when
                    val actual = tool(engine).open(URL)

                    // then
                    actual.isSuccess shouldBe true
                    val response = actual.getOrThrow()
                    response.status shouldBe 200
                    response.content { it.readBytes().decodeToString() } shouldBe "payload"
                }
            }

            "returns a 5xx as a success carrying its status" {
                runTest {
                    // given
                    val engine = MockEngine { respond("gone", HttpStatusCode.ServiceUnavailable) }

                    // when
                    val actual = tool(engine).open(URL)

                    // then
                    actual.isSuccess shouldBe true
                    val response = actual.getOrThrow()
                    response.status shouldBe 503
                    response.content { it.readBytes().decodeToString() } shouldBe "gone"
                }
            }

            "returns a transport failure raised by the engine as a failure" {
                runTest {
                    // given
                    val engine = MockEngine { throw IOException("connection reset") }

                    // when
                    val actual = tool(engine).open(URL)

                    // then
                    actual.isFailure shouldBe true
                    actual.exceptionOrNull().shouldBeInstanceOf<IOException>()
                }
            }

            "closes the stream after the block returns" {
                runTest {
                    // given
                    val body = ByteReadChannel("payload")
                    val engine = MockEngine { respond(body, HttpStatusCode.OK) }

                    // when
                    val firstByte = tool(engine).open(URL).getOrThrow().content { it.read() }

                    // then
                    firstByte shouldBe 'p'.code
                    body.isClosedForRead shouldBe true
                }
            }

            "reads an uncompressed body as it comes when the server does not compress" {
                runTest {
                    // given
                    val engine = MockEngine { respond("plain", HttpStatusCode.OK) }

                    // when
                    val body = tool(engine).open(URL).getOrThrow().content { it.readBytes().decodeToString() }

                    // then
                    body shouldBe "plain"
                    engine.requestHistory.single().headers[HttpHeaders.AcceptEncoding] shouldBe "identity"
                }
            }

            "unpacks the gzip body an adapter asked for" {
                runTest {
                    // given
                    val engine =
                        MockEngine {
                            respond(
                                gzip("compressed payload".toByteArray()),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentEncoding, "gzip"),
                            )
                        }

                    // when
                    val body =
                        tool(engine)
                            .open(URL, headers = mapOf(HttpHeaders.AcceptEncoding to "gzip"))
                            .getOrThrow()
                            .content { it.readBytes().decodeToString() }

                    // then
                    engine.requestHistory.single().headers[HttpHeaders.AcceptEncoding] shouldBe "gzip"
                    body shouldBe "compressed payload"
                }
            }

            "hands the Retry-After of the answer to the adapter" {
                runTest {
                    // given
                    val engine =
                        MockEngine {
                            respond(
                                "slow down",
                                HttpStatusCode.TooManyRequests,
                                headersOf(HttpHeaders.RetryAfter, "120"),
                            )
                        }

                    // when
                    val response = tool(engine).open(URL).getOrThrow()

                    // then
                    response.status shouldBe 429
                    response.headers[HttpHeaders.RetryAfter] shouldBe "120"
                }
            }
        }
    })
