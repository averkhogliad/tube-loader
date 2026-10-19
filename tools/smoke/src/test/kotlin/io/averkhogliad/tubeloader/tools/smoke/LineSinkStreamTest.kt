package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class LineSinkStreamTest :
    FreeSpec({

        "write" - {
            "emits a line per newline" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)

                stream.write("first\nsecond\n".toByteArray())

                lines shouldBe listOf("first", "second")
            }

            "holds a line until its newline arrives" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)

                stream.write("partial".toByteArray())

                lines shouldBe emptyList()
            }

            "joins a line split across writes" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)

                stream.write("head".toByteArray())
                stream.write("-tail\n".toByteArray())

                lines shouldBe listOf("head-tail")
            }

            "skips a blank line" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)

                stream.write("a\n\n\nb\n".toByteArray())

                lines shouldBe listOf("a", "b")
            }

            "drops a trailing carriage return" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)

                stream.write("windows\r\n".toByteArray())

                lines shouldBe listOf("windows")
            }
        }

        "drain" - {
            "emits the unterminated tail" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)
                stream.write("no newline".toByteArray())

                stream.drain()

                lines shouldBe listOf("no newline")
            }

            "emits nothing when the stream ended on a newline" {
                val lines = mutableListOf<String>()
                val stream = LineSinkStream(lines::add)
                stream.write("done\n".toByteArray())

                stream.drain()

                lines shouldBe listOf("done")
            }
        }
    })
