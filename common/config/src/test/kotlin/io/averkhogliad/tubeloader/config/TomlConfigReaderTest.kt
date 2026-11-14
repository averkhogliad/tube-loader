package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

private fun tomlLiteral(value: Any?): String =
    when (value) {
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0000", "\\u0000") + "\""
        is Collection<*> -> value.joinToString(", ", "[", "]") { tomlLiteral(it) }
        else -> value.toString()
    }

private fun tomlReader(values: Map<String, Any>): TomlConfigReader {
    val body =
        if (values.isEmpty()) {
            "# no values"
        } else {
            values.entries.joinToString("\n") { (key, value) -> "$key = ${tomlLiteral(value)}" }
        }
    return TomlConfigReader(TomlConfig.fromString(body))
}

class TomlConfigReaderTest :
    FreeSpec({

        include(configReaderContract(::tomlReader))

        "dotted keys of a table" - {
            "reads a value written inside a section" {
                // given
                val config =
                    TomlConfig.fromString(
                        """
                        [download.youtube]
                        resolve-timeout-ms = 15000
                        """.trimIndent(),
                    )

                // when
                val actual = TomlConfigReader(config).durationMillis("download.youtube.resolve-timeout-ms")

                // then
                actual shouldBe 15.seconds
            }

            "reads the statuses of the http tool block as whole numbers" {
                // given
                val config =
                    TomlConfig.fromString(
                        """
                        [download.http-tool]
                        per-attempt-timeout-ms = 60000
                        retriable-statuses = [408, 429, 503]
                        """.trimIndent(),
                    )

                // when
                val actual = TomlConfigReader(config).numberList("download.http-tool.retriable-statuses")

                // then
                actual shouldBe listOf(408, 429, 503)
            }
        }
    })
