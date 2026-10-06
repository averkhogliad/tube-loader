package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.TomlConfig
import io.averkhogliad.tubeloader.config.mapConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
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
            "returns the defaults when the block is missing" {
                // given
                val config = mapConfig("download.max-parallel-downloads" to "2")

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual shouldBe HttpToolConfig()
                actual.retryMaxAttempts shouldBe 5
                actual.retryBaseDelay shouldBe 250.milliseconds
                actual.retryRetriableStatuses shouldBe setOf(429, 500, 502, 503, 504)
            }

            "returns the defaults for a partly written block" {
                // given
                val config = toml("[download.http-tool]\nmax-attempts = 2")

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual.retryMaxAttempts shouldBe 2
                actual.readTimeout shouldBe 30.seconds
                actual.retryRandomizationFactor shouldBe 0.0
            }

            "reads every key of the block" {
                // given
                val config =
                    toml(
                        """
                        [download.http-tool]
                        connect-timeout-ms = 1500
                        read-timeout-ms = 4500
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
                actual.readTimeout shouldBe 4500.milliseconds
                actual.retryMaxAttempts shouldBe 7
                actual.retryBaseDelay shouldBe 100.milliseconds
                actual.retryRandomizationFactor shouldBe 0.25
                actual.retryRetriableStatuses shouldBe setOf(429, 503)
            }

            "keeps an unrelated block out of the reading" {
                // given
                val config = toml("[download.http-tool.retry]\nmax-attempts = 9")

                // when
                val actual = HttpToolConfig.fromConfig(config)

                // then
                actual.retryMaxAttempts shouldBe 5
            }

            "rejects a whole number that cannot be read" {
                // given
                val config = toml("[download.http-tool]\nbase-delay-ms = \"abc\"")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.base-delay-ms must be a whole number, got abc"
            }

            "rejects an attempt count below one" {
                // given
                val config = toml("[download.http-tool]\nmax-attempts = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.max-attempts must be a whole number of at least 1, got 0"
            }

            "rejects a non positive timeout" {
                // given
                val config = toml("[download.http-tool]\nread-timeout-ms = 0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.read-timeout-ms must be positive, got 0"
            }

            "rejects a randomization factor outside the unit interval" {
                // given
                val config = toml("[download.http-tool]\nrandomization-factor = 2.0")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromConfig(config) }

                // then
                thrown.message shouldBe
                    "download.http-tool.randomization-factor must be within 0.0..1.0, got 2.0"
            }

            "rejects a status list that is not a list" {
                // given
                val config = toml("[download.http-tool]\nretriable-statuses = 503")

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
                val config = toml("[other.http-tool]\nmax-attempts = 6")

                // when
                val actual = AppConfig.fromConfig(config, keyPrefix = "other")

                // then
                actual.httpTool.retryMaxAttempts shouldBe 6
            }
        }
    })
