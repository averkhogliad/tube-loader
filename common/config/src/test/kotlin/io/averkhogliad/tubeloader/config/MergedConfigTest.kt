package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next

private fun configOf(table: Map<String, Any>): Config =
    object : Config {
        override val keys: Set<String> = table.keys

        override fun getOrNull(path: String): String? = null

        @Suppress("UNCHECKED_CAST")
        override fun getTableOrNull(path: String): Map<String, Any>? =
            table[path] as? Map<String, Any>
    }

@Suppress("UNCHECKED_CAST")
private fun nestedOf(table: Map<String, Any>): Map<String, Any> = table["nested"] as Map<String, Any>

class MergedConfigTest : FreeSpec({

    "getOrNull" - {
        "later source overrides earlier one" {
            // given
            val first = mapConfig("a" to "1", "b" to "2")
            val second = mapConfig("b" to "3")

            // when
            val config = MergedConfig(first, second)

            // then
            config.getOrNull("a") shouldBe "1"
            config.getOrNull("b") shouldBe "3"
        }

        "later source does not erase keys it lacks" {
            // given
            val config = MergedConfig(
                mapConfig("a" to "1"),
                mapConfig("b" to "2"),
            )

            // when
            val actual = config.getOrNull("a")

            // then
            actual shouldBe "1"
        }

        "returns null when no source has the key" {
            // given
            val config = MergedConfig(emptyList())

            // when
            val actual = config.getOrNull("missing")

            // then
            actual shouldBe null
        }

        "reads back a generated pair from the overriding source" {
            // given
            val pair = Arb.flatTables().next().entries.single()
            val config = MergedConfig(mapConfig(pair.key to "old"), mapConfig(pair.key to pair.value.toString()))

            // when
            val actual = config.getOrNull(pair.key)

            // then
            actual shouldBe pair.value.toString()
        }
    }

    "getTableOrNull" - {
        "merges nested tables key by key" {
            // given
            val first = mapConfig(
                "download.max" to "2",
                "download.host" to "rutube",
            )
            val second = mapConfig("download.max" to "5")

            // when
            val config = MergedConfig(first, second)
            val table = config.getTableOrNull("download")

            // then
            table shouldBe mapOf(
                "max" to "5",
                "host" to "rutube",
            )
        }

        "table from a later source does not erase sibling keys of earlier source" {
            // given
            val first = mapConfig("download.max" to "2")
            val second = mapConfig("other.enabled" to "true")

            // when
            val config = MergedConfig(first, second)
            val download = config.getTableOrNull("download")

            // then
            download shouldBe mapOf("max" to "2")
        }

        "keeps the earlier table when a later source has no table for the path" {
            // given
            val first = mapConfig("download.max" to "2")
            val second = TomlConfig.fromString("other.enabled = true")

            // when
            val table = MergedConfig(first, second).getTableOrNull("download")

            // then
            table shouldBe mapOf("max" to "2")
        }

        "takes the table from the only source that has one" {
            // given
            val first = TomlConfig.fromString("other.enabled = true")
            val second = mapConfig("download.max" to "2")

            // when
            val table = MergedConfig(first, second).getTableOrNull("download")

            // then
            table shouldBe mapOf("max" to "2")
        }

        "recursively merges nested tables from both sources" {
            // given
            val first = TomlConfig.fromString(
                """
                [download]
                host = "rutube"
                [download.limits]
                per-host = 1
                """.trimIndent(),
            )
            val second = TomlConfig.fromString(
                """
                [download]
                [download.limits]
                total = 5
                """.trimIndent(),
            )

            // when
            val table = MergedConfig(first, second).getTableOrNull("download")

            // then
            table shouldBe mapOf(
                "host" to "rutube",
                "limits" to mapOf("per-host" to 1L, "total" to 5L),
            )
        }

        "lets a later scalar replace an earlier nested table" {
            // given
            val first = TomlConfig.fromString(
                """
                [download]
                [download.limits]
                per-host = 1
                """.trimIndent(),
            )
            val second = TomlConfig.fromString(
                """
                [download]
                limits = 5
                """.trimIndent(),
            )

            // when
            val table = MergedConfig(first, second).getTableOrNull("download")

            // then
            table shouldBe mapOf("limits" to 5L)
        }

        "lets a later nested table replace an earlier scalar" {
            // given
            val first = TomlConfig.fromString(
                """
                [download]
                limits = 5
                """.trimIndent(),
            )
            val second = TomlConfig.fromString(
                """
                [download]
                [download.limits]
                per-host = 1
                """.trimIndent(),
            )

            // when
            val table = MergedConfig(first, second).getTableOrNull("download")

            // then
            table shouldBe mapOf("limits" to mapOf("per-host" to 1L))
        }

        "merges a generated nested table from both sources" {
            // given
            val first = Arb.nestedTables().next()
            val second = Arb.nestedTables().next()
            val expected = nestedOf(first) + nestedOf(second)

            // when
            val table = MergedConfig(configOf(first), configOf(second)).getTableOrNull("nested")

            // then
            table shouldBe expected
        }
    }

    "keys" - {
        "unions keys of all sources" {
            // given
            val config = MergedConfig(
                mapConfig("a" to "1"),
                mapConfig("b" to "2"),
            )

            // when
            val actual = config.keys

            // then
            actual shouldBe setOf("a", "b")
        }
    }
})
