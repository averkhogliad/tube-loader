package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class SmokeFlowTest :
    FreeSpec({

        "targetName" - {
            "names the target after the media id of the source when the command gave no name" {
                // given
                val target = SmokeTarget("https://www.youtube.com/watch?v=abc123", null)

                // when
                val actual = targetName(target, "abc123")

                // then
                actual shouldBe "abc123"
            }

            "keeps the name the command gave" {
                // given
                val target = SmokeTarget("https://rutube.ru/video/abc/", "clip")

                // when
                val actual = targetName(target, "abc")

                // then
                actual shouldBe "clip"
            }
        }

        "sanitize" - {
            "replaces the characters a file name cannot hold" {
                // given
                val name = "a/b:c"

                // when
                val actual = sanitize(name)

                // then
                actual shouldBe "a_b_c"
            }

            "falls back to a plain name when nothing is left" {
                // given
                val name = "   "

                // when
                val actual = sanitize(name)

                // then
                actual shouldBe "video"
            }
        }
    })
