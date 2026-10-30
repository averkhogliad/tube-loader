package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class CliArgsTest :
    FreeSpec({

        "parseCli" - {
            "keeps the default target dir and parallelism when no flag is given" {
                // given
                val rawArgs = emptyArray<String>()

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe emptyList()
                actual.targetDir shouldBe DEFAULT_TARGET_DIR
                actual.parallel shouldBe DEFAULT_PARALLEL
                actual.cancelAfter shouldBe null
            }

            "reads the id and name pair" {
                // given
                val rawArgs = arrayOf("-id", "abc", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget("https://rutube.ru/video/abc/", "clip"))
            }

            "reads a rutube url as a target" {
                // given
                val rawArgs = arrayOf("https://rutube.ru/video/abc123/")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget("https://rutube.ru/video/abc123/", "abc123"))
            }

            "reads several targets" {
                // given
                val rawArgs = arrayOf("https://rutube.ru/video/abc123/", "-id", "def", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe
                    listOf(
                        SmokeTarget("https://rutube.ru/video/abc123/", "abc123"),
                        SmokeTarget("https://rutube.ru/video/def/", "clip"),
                    )
            }

            "reads the cancel-after delay in seconds" {
                // given
                val rawArgs = arrayOf("-cancel-after", "7")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.cancelAfter shouldBe 7.seconds
            }

            "rejects a target that is not a url" {
                // given
                val rawArgs = arrayOf("abc123")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects a cancel-after delay that is not a number" {
                // given
                val rawArgs = arrayOf("-cancel-after", "soon")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }
        }
    })
