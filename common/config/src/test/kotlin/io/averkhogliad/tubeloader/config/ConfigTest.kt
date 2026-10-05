package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next

class ConfigTest :
    FreeSpec({

        "getOrNull with a transform" - {
            "applies the transform to the value that is present" {
                // given
                val config = TomlConfig.fromString("download.max = 2")

                // when
                val actual = config.getOrNull("download.max") { it.toInt() }

                // then
                actual shouldBe 2
            }

            "returns null and never runs the transform when the path is absent" {
                // given
                val config = TomlConfig.fromString("download.max = 2")
                var transformRan = false

                // when
                val actual = config.getOrNull("missing") {
                    transformRan = true
                    it.toInt()
                }

                // then
                actual.shouldBeNull()
                transformRan shouldBe false
            }

            "transforms a generated value that is present" {
                // given
                val key = Arb.configKeys().next()
                val value = Arb.configValues().next()
                val config = mapConfig(key to value)

                // when
                val actual = config.getOrNull(key) { it.uppercase() }

                // then
                actual shouldBe value.uppercase()
            }
        }

        "getTableOrNull with a transform" - {
            "applies the transform to the table that is present" {
                // given
                val config = TomlConfig.fromString("download.max = 2")

                // when
                val actual = config.getTableOrNull("download") { it.keys }

                // then
                actual shouldBe setOf("max")
            }

            "returns null and never runs the transform when the path is absent" {
                // given
                val config = TomlConfig.fromString("download.max = 2")
                var transformRan = false

                // when
                val actual = config.getTableOrNull("missing") {
                    transformRan = true
                    it.keys
                }

                // then
                actual.shouldBeNull()
                transformRan shouldBe false
            }
        }
    })
