package io.averkhogliad.tubeloader.core.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class SourceTest :
    FreeSpec({

        "constructor" - {
            "carries the code and the name of the source" {
                // when
                val actual = Source("rutube", "Rutube")

                // then
                actual.code shouldBe "rutube"
                actual.displayName shouldBe "Rutube"
            }

            "rejects a blank code" {
                // when + then
                shouldThrow<IllegalArgumentException> {
                    Source(" ", "Rutube")
                }
            }

            "rejects a code that is not lowercase" {
                // when + then
                shouldThrow<IllegalArgumentException> {
                    Source("Rutube", "Rutube")
                }
            }
        }
    })
