package io.averkhogliad.tubeloader.core.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class QualityTest :
    FreeSpec({

        "constructor" - {
            "leaves the height of a variant the source did not name unset" {
                // when
                val quality = Quality("hls-360", TrackKind.Video, "360p")

                // then
                quality.height shouldBe null
            }
        }

        "ofHeight" - {
            "builds a video quality named by its height" {
                // when
                val quality = Quality.ofHeight(1080)

                // then
                quality shouldBe Quality("1080p", TrackKind.Video, "1080p", height = 1080)
            }
        }
    })
