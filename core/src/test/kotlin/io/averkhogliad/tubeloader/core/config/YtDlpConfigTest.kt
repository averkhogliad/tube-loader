package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.ConfigReader
import io.averkhogliad.tubeloader.config.MemoryConfigReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TABLE = YtDlpConfig.DEFAULT_TABLE

/**
 * The block is read through the in-memory source: parsing a source is the contract of
 * `common:config`, while the settings here own the keys and the semantics.
 */
private fun reader(vararg pairs: Pair<String, Any>): ConfigReader = MemoryConfigReader(pairs.toMap())

private fun key(name: String) = "$TABLE.$name"

private val PATHS = ToolPaths.of(Path.of("home"), "Linux")

class YtDlpConfigTest :
    FreeSpec({

        "fromReader" - {
            "answers the defaults when the block is missing" {
                // given
                val source = reader()

                // when
                val actual = YtDlpConfig.fromReader(source, PATHS)

                // then
                actual.paths shouldBe PATHS
                actual.workDirectory shouldBe PATHS.homeDir.resolve(YtDlpConfig.DEFAULT_WORK_DIR)
                actual.binaryPath shouldBe null
                actual.resolveTimeout shouldBe 60.seconds
                actual.concurrentFragments shouldBe 1
                actual.retryMaxAttempts shouldBe 5
                actual.retryBaseDelay shouldBe 250.milliseconds
                actual.retryRandomizationFactor shouldBe 0.0
            }

            "reads every key of the block" {
                // given
                val source =
                    reader(
                        key("work-directory") to "work",
                        key("binary-path") to "opt/yt-dlp",
                        key("resolve-timeout-ms") to 15000,
                        key("concurrent-fragments") to 4,
                        key("max-attempts") to 3,
                        key("base-delay-ms") to 500,
                        key("randomization-factor") to 0.2,
                    )

                // when
                val actual = YtDlpConfig.fromReader(source, PATHS)

                // then
                actual.workDirectory shouldBe Path.of("work")
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
                val source =
                    reader(
                        "download.http-tool.concurrent-fragments" to 9,
                        "download.http-tool.resolve-timeout-ms" to 1000,
                    )

                // when
                val actual = YtDlpConfig.fromReader(source, PATHS)

                // then
                actual.resolveTimeout shouldBe 60.seconds
                actual.concurrentFragments shouldBe 1
            }

            "reads the block of a prefixed key" {
                // given
                val source = reader("other.youtube.concurrent-fragments" to 2)

                // when
                val actual = YtDlpConfig.fromReader(source, PATHS, "other.youtube")

                // then
                actual.concurrentFragments shouldBe 2
            }

            "raises for an empty binary path" {
                // given
                val source = reader(key("binary-path") to "  ")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromReader(source, PATHS) }

                // then
                thrown.message shouldBe "download.youtube.binary-path must be a path, got an empty value"
            }

            "raises for a non-positive resolve timeout" {
                // given
                val source = reader(key("resolve-timeout-ms") to 0)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromReader(source, PATHS) }

                // then
                thrown.message shouldBe "resolveTimeout must be positive, got 0s"
            }

            "raises for a timeout that is not a whole number" {
                // given
                val source = reader(key("resolve-timeout-ms") to "soon")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromReader(source, PATHS) }

                // then
                thrown.message shouldBe "download.youtube.resolve-timeout-ms must be a whole number, got soon"
            }

            "raises for fewer than one fragment" {
                // given
                val source = reader(key("concurrent-fragments") to 0)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { YtDlpConfig.fromReader(source, PATHS) }

                // then
                thrown.message shouldBe "concurrentFragments must be at least 1, got 0"
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
