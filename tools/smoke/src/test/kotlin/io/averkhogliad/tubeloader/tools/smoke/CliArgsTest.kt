package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class CliArgsTest :
    FreeSpec({

        "parseCli" - {
            "keeps the default target dir and parallelism when no flag is given" {
                val actual = parseCli(emptyArray())

                actual.targetDir shouldBe DEFAULT_TARGET_DIR
                actual.parallel shouldBe DEFAULT_PARALLEL
                actual.cancelAfter shouldBe null
                actual.cancelTaskId shouldBe null
            }

            "reads the id and name pair" {
                val actual = parseCli(arrayOf("-id", "abc", "-name", "clip"))

                actual.pairs shouldBe listOf("abc" to "clip")
            }

            "reads the cancel-after delay in seconds" {
                val actual = parseCli(arrayOf("-cancel-after", "7"))

                actual.cancelAfter shouldBe 7.seconds
            }

            "reads the cancel task id as hex" {
                val actual = parseCli(arrayOf("-cancel", "1234abcd"))

                actual.cancelTaskId shouldBe TaskId(0x1234abcd)
            }

            "rejects a cancel task id that is not hex" {
                shouldThrow<IllegalStateException> {
                    parseCli(arrayOf("-cancel", "zz"))
                }
            }

            "rejects a cancel-after delay that is not a number" {
                shouldThrow<IllegalStateException> {
                    parseCli(arrayOf("-cancel-after", "soon"))
                }
            }
        }
    })
