package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TaskIdTest :
    FreeSpec({
        "toString" - {
            "renders the value as eight hex digits" {
                TaskId(0).toString() shouldBe "00000000"
                TaskId(1).toString() shouldBe "00000001"
                TaskId(0x3F2A1B0C).toString() shouldBe "3f2a1b0c"
                TaskId(-1).toString() shouldBe "ffffffff"
            }
        }
    })
