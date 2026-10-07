package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.TomlConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The block under test is read through a real TOML parse: the seam hands out `Any` values of the type
 * the parser produces, and a hand-written map would not prove that an array of statuses arrives as a
 * collection of whole numbers.
 */
private fun toml(body: String) = TomlConfig.fromString(body.trimIndent())

class HttpToolConfigTest :
    FreeSpec({

        "fromConfig" - {
            "raises for the required key when the block is missing" {
                // given
                val config = toml("[download]\nmax-parallel-downloads = 2")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe "download.http-tool.per-attempt-timeout-ms is required"
            }

            "returns the defaults for a partly written block" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nmax-attempts = 2")

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual.retryMaxAttempts shouldBe 2
                actual.requestTimeout shouldBe 30.seconds
                actual.retryRandomizationFactor shouldBe 0.0
            }

            "reads every key of the block" {
                // given
                val config =
                    toml(
                        """
                        [download.http-tool]
                        connect-timeout-ms = 1500
                        request-timeout-ms = 4500
                        per-attempt-timeout-ms = 30000
                        max-attempts = 7
                        base-delay-ms = 100
                        randomization-factor = 0.25
                        retriable-statuses = [429, 503]
                        """,
                    )

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual.connectTimeout shouldBe 1500.milliseconds
                actual.requestTimeout shouldBe 4500.milliseconds
                actual.perAttemptTimeout shouldBe 30.seconds
                actual.retryMaxAttempts shouldBe 7
                actual.retryBaseDelay shouldBe 100.milliseconds
                actual.retryRandomizationFactor shouldBe 0.25
                actual.retryRetriableStatuses shouldBe setOf(429, 503)
            }

            "keeps an unrelated block out of the reading" {
                // given
                val config =
                    toml(
                        """
                        [download.http-tool]
                        per-attempt-timeout-ms = 30000

                        [download.http-tool.retry]
                        max-attempts = 9
                        """,
                    )

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual.retryMaxAttempts shouldBe 5
            }

            "raises for the required key when the block writes every other key" {
                // given
                val config =
                    toml(
                        """
                        [download.http-tool]
                        connect-timeout-ms = 1500
                        request-timeout-ms = 4500
                        max-attempts = 7
                        base-delay-ms = 100
                        randomization-factor = 0.25
                        retriable-statuses = [429, 503]
                        """,
                    )

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe "download.http-tool.per-attempt-timeout-ms is required"
            }

            "rejects a zero per attempt timeout" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.per-attempt-timeout-ms must be positive, got 0"
            }

            "rejects a whole number that cannot be read" {
                // given
                val config =
                    toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nbase-delay-ms = \"abc\"")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.base-delay-ms must be a whole number, got abc"
            }

            "rejects an attempt count below one" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nmax-attempts = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.max-attempts must be a whole number of at least 1, got 0"
            }

            "rejects a non positive timeout" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nrequest-timeout-ms = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.request-timeout-ms must be positive, got 0"
            }

            "accepts a zero retry base delay, which repeats without waiting" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nbase-delay-ms = 0")

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual.retryBaseDelay shouldBe Duration.ZERO
            }

            "rejects a randomization factor outside the unit interval" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nrandomization-factor = 2.0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.randomization-factor must be within 0.0..1.0, got 2.0"
            }

            "rejects a status list that is not a list" {
                // given
                val config = toml("[download.http-tool]\nper-attempt-timeout-ms = 30000\nretriable-statuses = 503")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.retriable-statuses must be a list of statuses, got 503"
            }
        }

        "AppConfig" - {
            "carries the http tool block" {
                // given
                val config =
                    toml(
                        """
                        [download]
                        max-parallel-downloads = 2

                        [download.http-tool]
                        per-attempt-timeout-ms = 30000
                        max-attempts = 4
                        """,
                    )

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual.maxParallelDownloads shouldBe 2
                actual.httpTool.retryMaxAttempts shouldBe 4
            }

            "reads the block under its own key prefix" {
                // given
                val config = toml("[other.http-tool]\nper-attempt-timeout-ms = 30000\nmax-attempts = 6")

                // when
                val actual = AppConfig.fromConfig(config, keyPrefix = "other")

                // then
                actual.httpTool.retryMaxAttempts shouldBe 6
            }
        }
    })
