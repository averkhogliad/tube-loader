package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ConfigProviderTest :
    FreeSpec({

        "load" - {
            "later source overrides earlier one and missing sources are skipped" {
                // given
                val base = object : ConfigSource {
                    override fun load(): Config = mapConfig("a" to "1", "b" to "2")
                }
                val absent = object : ConfigSource {
                    override fun load(): Config? = null
                }
                val override = object : ConfigSource {
                    override fun load(): Config = mapConfig("b" to "3", "c" to "4")
                }

                // when
                val config = ConfigProvider()
                    .addSource(base, absent, override)
                    .load()

                // then
                config.getOrNull("a") shouldBe "1"
                config.getOrNull("b") shouldBe "3"
                config.getOrNull("c") shouldBe "4"
            }
        }
    })
