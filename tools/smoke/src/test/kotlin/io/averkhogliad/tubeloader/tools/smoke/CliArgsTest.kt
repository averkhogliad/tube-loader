package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.app.AppOverrides
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class CliArgsTest :
    FreeSpec({

        "parseCli" - {
            "leaves the target dir and the parallelism to the config when no flag is given" {
                // given
                val rawArgs = emptyArray<String>()

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe emptyList()
                actual.targetDir shouldBe null
                actual.parallel shouldBe null
                actual.cancelAfter shouldBe null
            }

            "reads the target dir and the parallelism the command names" {
                // given
                val rawArgs = arrayOf("-out", "D:/videos", "-parallel", "4")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targetDir shouldBe "D:/videos"
                actual.parallel shouldBe 4
            }

            "rejects a parallelism below one" {
                // given
                val rawArgs = arrayOf("-parallel", "0")

                // when + then
                shouldThrow<IllegalArgumentException> {
                    parseCli(rawArgs)
                }
            }

            "reads the code, the id and the name of a target" {
                // given
                val rawArgs = arrayOf("-id", "rutube:abc", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget.ById("rutube", "abc", "clip"))
            }

            "lowercases the code and keeps the id as it is" {
                // given
                val rawArgs = arrayOf("-id", "YouTube:AbC123", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget.ById("youtube", "AbC123", "clip"))
            }

            "splits the code and the id at the first colon" {
                // given
                val rawArgs = arrayOf("-id", "youtube:abc:def", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget.ById("youtube", "abc:def", "clip"))
            }

            "reads a rutube url as a target" {
                // given
                val rawArgs = arrayOf("-url", "https://rutube.ru/video/abc123/", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget.ByUrl("https://rutube.ru/video/abc123/", "clip"))
            }

            "reads a youtube url as a target" {
                // given
                val rawArgs = arrayOf("-url", "https://www.youtube.com/watch?v=abc123", "-name", "clip")

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe listOf(SmokeTarget.ByUrl("https://www.youtube.com/watch?v=abc123", "clip"))
            }

            "reads several targets, each with a name of its own" {
                // given
                val rawArgs =
                    arrayOf(
                        "-url",
                        "https://rutube.ru/video/abc123/",
                        "-name",
                        "one",
                        "-url",
                        "https://youtu.be/xyz",
                        "-name",
                        "two",
                        "-id",
                        "rutube:def",
                        "-name",
                        "three",
                    )

                // when
                val actual = parseCli(rawArgs)

                // then
                actual.targets shouldBe
                    listOf(
                        SmokeTarget.ByUrl("https://rutube.ru/video/abc123/", "one"),
                        SmokeTarget.ByUrl("https://youtu.be/xyz", "two"),
                        SmokeTarget.ById("rutube", "def", "three"),
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

            "rejects an argument that is not a flag" {
                // given
                val rawArgs = arrayOf("abc123")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects a url target without a name" {
                // given
                val rawArgs = arrayOf("-url", "https://youtu.be/xyz")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects a url target whose value is not a url" {
                // given
                val rawArgs = arrayOf("-url", "abc123")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects a url flag without a value" {
                // given
                val rawArgs = arrayOf("-url")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects a name that does not follow its target" {
                // given
                val rawArgs = arrayOf("-url", "https://youtu.be/xyz", "-out", "out", "-name", "clip")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects a name before any target" {
                // given
                val rawArgs = arrayOf("-name", "clip")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects an id without a code of the source" {
                // given
                val rawArgs = arrayOf("-id", "abc123")

                // when + then
                shouldThrow<IllegalStateException> {
                    parseCli(rawArgs)
                }
            }

            "rejects an id without a name of the file" {
                // given
                val rawArgs = arrayOf("-id", "rutube:abc123")

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

            "tells the usage in the error of a malformed id" {
                // given
                val rawArgs = arrayOf("-id", "abc123")

                // when
                val failure = shouldThrow<IllegalStateException> { parseCli(rawArgs) }

                // then
                failure.message!! shouldContain "Usage:"
            }
        }

        "usage" - {
            "names the default target directory" {
                // given
                val usage = CliUsage.USAGE

                // when + then
                usage shouldContain DEFAULT_TARGET_DIR
            }

            "names the code and the id of a target" {
                // given
                val usage = CliUsage.USAGE

                // when + then
                usage shouldContain "<code>:<mediaId>"
            }
        }

        "overrides" - {
            "passes the values the command names" {
                // given
                val cli = parseCli(arrayOf("-out", "D:/videos", "-parallel", "4"))

                // when
                val actual = cli.overrides()

                // then
                actual shouldBe AppOverrides(maxParallelDownloads = 4, defaultTargetDir = Path.of("D:/videos"))
            }

            "leaves the values the command does not name to the config" {
                // given
                val cli = parseCli(emptyArray())

                // when
                val actual = cli.overrides()

                // then
                actual shouldBe AppOverrides()
            }
        }
    })
