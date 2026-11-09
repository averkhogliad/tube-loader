package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class SmokeFlowTest :
    FreeSpec({

        "targetName" - {
            "keeps the name the command gave to a url target" {
                // given
                val target = SmokeTarget.ByUrl("https://www.youtube.com/watch?v=abc123", "clip")

                // when
                val actual = targetName(target)

                // then
                actual shouldBe "clip"
            }

            "keeps the name the command gave to an id target" {
                // given
                val target = SmokeTarget.ById("rutube", "abc", "clip")

                // when
                val actual = targetName(target)

                // then
                actual shouldBe "clip"
            }
        }
    })
