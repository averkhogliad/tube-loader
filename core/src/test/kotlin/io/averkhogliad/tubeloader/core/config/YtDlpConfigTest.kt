package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.TomlConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The block under test is read through a real TOML parse: the seam hands out `Any` values of the type
 * the parser produces, and a hand-written map would not prove that a whole number arrives as one.
 */
private fun toml(body: String) = TomlConfig.fromString(body.trimIndent())

private val PATHS = ToolPaths.of(Path.of("home"), "Linux")

class YtDlpConfigTest :
    FreeSpec({

        "fromConfig" - {
            "answers the defaults when the block is missing" {
                // given
                val config = toml("[download]\nmax-parallel-downloads = 2")

                // when
                val actual = YtDlpConfig.fromConfig(config, PATHS)

                // then
                actual.paths shouldBe PATHS
                actual.binaryPath shouldBe null
                actual.resolveTimeout shouldBe 60.seconds
                actual.concurrentFragments shouldBe 1
                actual.retryMaxAttempts shouldBe 5
                actual.retryBaseDelay shouldBe 250.milliseconds
                actual.retryRandomizationFactor shouldBe 0.0
            }

            "reads every key of the block" {
                // given
                val config =
                    toml(
                        """
                        [download.youtube]
                        binary-path = "opt/yt-dlp"
                        resolve-timeout-ms = 15000
                        concurrent-fragments = 4
                        max-attempts = 3
                        base-delay-ms = 500
                        randomization-factor = 0.2
                        """,
                    )

                // when
                val actual = YtDlpConfig.fromConfig(config, PATHS)

                // then
                actual.binaryPath shouldBe Path.of("opt", "yt-dlp")
                actual.resolveTimeout shouldBe 15000.milliseconds
                actual.concurrentFragments shouldBe 4
                actual.retryMaxAttempts shouldBe 3
                actual.retryBaseDelay shouldBe 500.milliseconds
                actual.retryRandomizationFactor shouldBe 0.2
                actual.paths shouldBe PATHS
            }

            "keeps an unrelated block out of the reading" {
                // given
                val config =
                    toml(
                        """
                        [download.http-tool]
                        concurrent-fragments = 9
                        resolve-timeout-ms = 1000
                        """,
                    )

                // when
                val actual = YtDlpConfig.fromConfig(config, PATHS)

                // then
                actual.resolveTimeout shouldBe 60.seconds
                actual.concurrentFragments shouldBe 1
            }

            "reads the block of a prefixed key" {
                // given
                val config =
                    toml(
                        """
                        [other.youtube]
                        concurrent-fragments = 2
                        """,
                    )

                // when
                val actual = YtDlpConfig.fromConfig(config, PATHS, "other.youtube")

                // then
                actual.concurrentFragments shouldBe 2
            }

            "raises for an empty binary path" {
                // given
                val config = toml("[download.youtube]\nbinary-path = \"  \"")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromConfig(config, PATHS) }

                // then
                thrown.message shouldBe "download.youtube.binary-path must be a path, got an empty value"
            }

            "raises for a non-positive resolve timeout" {
                // given
                val config = toml("[download.youtube]\nresolve-timeout-ms = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromConfig(config, PATHS) }

                // then
                thrown.message shouldBe "download.youtube.resolve-timeout-ms must be positive, got 0"
            }

            "raises for a timeout that is not a whole number" {
                // given
                val config = toml("[download.youtube]\nresolve-timeout-ms = \"soon\"")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromConfig(config, PATHS) }

                // then
                thrown.message shouldBe "download.youtube.resolve-timeout-ms must be a whole number, got soon"
            }

            "raises for fewer than one fragment" {
                // given
                val config = toml("[download.youtube]\nconcurrent-fragments = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromConfig(config, PATHS) }

                // then
                thrown.message shouldBe
                    "download.youtube.concurrent-fragments must be a whole number of at least 1, got 0"
            }
        }

        "YtDlpConfig" - {
            "rejects a non-positive resolve timeout" {
                shouldThrow<IllegalArgumentException> {
                    YtDlpConfig(paths = PATHS, resolveTimeout = 0.milliseconds)
                }
            }

            "rejects fewer than one fragment" {
                shouldThrow<IllegalArgumentException> {
                    YtDlpConfig(paths = PATHS, concurrentFragments = 0)
                }
            }

            "rejects fewer than one retry attempt" {
                shouldThrow<IllegalArgumentException> {
                    YtDlpConfig(paths = PATHS, retryMaxAttempts = 0)
                }
            }

            "rejects a negative retry base delay" {
                shouldThrow<IllegalArgumentException> {
                    YtDlpConfig(paths = PATHS, retryBaseDelay = (-1).milliseconds)
                }
            }

            "rejects a retry randomization factor out of range" {
                shouldThrow<IllegalArgumentException> {
                    YtDlpConfig(paths = PATHS, retryRandomizationFactor = 1.5)
                }
            }
        }
    })
