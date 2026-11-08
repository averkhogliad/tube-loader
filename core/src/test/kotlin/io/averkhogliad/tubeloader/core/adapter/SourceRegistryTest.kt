package io.averkhogliad.tubeloader.core.adapter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class SourceRegistryTest :
    FreeSpec({

        "adaptersByCode" - {
            "registers an adapter under its code" {
                // given
                val adapter = FakeSourceAdapter(code = "rutube")

                // when
                val actual = adaptersByCode(listOf("rutube" to adapter))

                // then
                actual shouldBe mapOf("rutube" to adapter)
            }

            "keeps the order of the registration" {
                // given
                val first = FakeSourceAdapter(code = "rutube")
                val second = FakeSourceAdapter(code = "youtube")

                // when
                val actual = adaptersByCode(listOf("rutube" to first, "youtube" to second))

                // then
                actual.keys.toList() shouldBe listOf("rutube", "youtube")
            }

            "rejects a repeated code" {
                // given
                val first = FakeSourceAdapter(code = "rutube")
                val second = FakeSourceAdapter(code = "rutube")

                // when + then
                shouldThrow<IllegalArgumentException> {
                    adaptersByCode(listOf("rutube" to first, "rutube" to second))
                }
            }
        }
    })
