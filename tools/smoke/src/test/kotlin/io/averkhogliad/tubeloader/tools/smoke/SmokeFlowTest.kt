package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class SmokeFlowTest :
    FreeSpec({

        "targetName" - {
            "names the target after the media id of the source when the command gave no name" {
                // given
                val target = SmokeTarget.ByUrl("https://www.youtube.com/watch?v=abc123")

                // when
                val actual = targetName(target, "abc123")

                // then
                actual shouldBe "abc123"
            }

            "keeps the name the command gave" {
                // given
                val target = SmokeTarget.ById("rutube", "abc", "clip")

                // when
                val actual = targetName(target, "abc")

                // then
                actual shouldBe "clip"
            }

            "falls back to the url while the source has not answered yet" {
                // given
                val target = SmokeTarget.ByUrl("https://rutube.ru/video/abc/")

                // when
                val actual = targetName(target)

                // then
                actual shouldBe "https://rutube.ru/video/abc/"
            }
        }
    })
