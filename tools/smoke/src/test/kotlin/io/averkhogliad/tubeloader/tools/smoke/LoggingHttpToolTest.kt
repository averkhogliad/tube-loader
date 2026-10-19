package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.httpBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException

private const val OK = 200
private const val NOT_FOUND = 404
private const val FORBIDDEN = 403

class LoggingHttpToolTest :
    FreeSpec({

        "open" - {
            "reports a status the adapter turns into ExtractorBroken" {
                val notes = mutableListOf<String>()
                val delegate = FakeHttpTool().respondWith(ByteArray(0), status = FORBIDDEN)
                val tool = LoggingHttpTool(delegate, notes::add)

                val result = tool.open("https://rutube.ru/api/play/options/x/")

                result.isSuccess shouldBe true
                notes.single() shouldContain "403"
            }

            "reports a transport failure the adapter turns into NetworkTransient" {
                val notes = mutableListOf<String>()
                val delegate = FakeHttpTool().failingWith(IOException("connection reset"))
                val tool = LoggingHttpTool(delegate, notes::add)

                val result = tool.open("https://rutube.ru/segment.ts")

                result.isFailure shouldBe true
                notes.single() shouldContain "connection reset"
            }

            "stays silent on an accepted status" {
                val notes = mutableListOf<String>()
                val delegate = FakeHttpTool().respondWith(ByteArray(0), status = OK)
                val tool = LoggingHttpTool(delegate, notes::add)

                tool.open("https://rutube.ru/segment.ts")

                notes shouldBe emptyList()
            }

            "reports a missing resource by its status" {
                val notes = mutableListOf<String>()
                val delegate = FakeHttpTool().respondWith(ByteArray(0), status = NOT_FOUND)
                val tool = LoggingHttpTool(delegate, notes::add)

                tool.open("https://rutube.ru/api/play/options/x/")

                notes.single() shouldContain "404"
            }

            "passes the response through untouched" {
                val delegate = FakeHttpTool().respondWith("payload".toByteArray(), status = OK)
                val tool = LoggingHttpTool(delegate, {})

                val result = tool.open("https://rutube.ru/segment.ts")

                result.getOrNull()?.content { it.readBytes() }?.decodeToString() shouldBe "payload"
            }
        }

        "close" - {
            "closes the delegate it borrowed" {
                var closed = false
                val delegate =
                    object : io.averkhogliad.tubeloader.core.port.HttpTool {
                        override suspend fun open(url: String, headers: Map<String, String>) =
                            Result.success(httpBody(ByteArray(0)))

                        override fun close() {
                            closed = true
                        }
                    }

                LoggingHttpTool(delegate, {}).close()

                closed shouldBe true
            }
        }
    })
