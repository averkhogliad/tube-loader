package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.ConfigReader
import io.averkhogliad.tubeloader.config.MemoryConfigReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TABLE = HttpToolConfig.DEFAULT_TABLE

/**
 * The block is read through the in-memory source: parsing a source is the contract of
 * `common:config`, while the settings here own the keys and the semantics.
 */
private fun reader(vararg pairs: Pair<String, Any>): ConfigReader = MemoryConfigReader(pairs.toMap())

private fun key(name: String) = "$TABLE.$name"

private val perAttempt = key("per-attempt-timeout-ms") to 30000

class HttpToolConfigTest :
    FreeSpec({

        "fromReader" - {
            "raises for the required key when the block is missing" {
                // given
                val source = reader()

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "download.http-tool.per-attempt-timeout-ms is required"
            }

            "returns the defaults for a partly written block" {
                // given
                val source = reader(perAttempt, key("max-attempts") to 2)

                // when
                val actual = HttpToolConfig.fromReader(source)

                // then
                actual.retryMaxAttempts shouldBe 2
                actual.requestTimeout shouldBe 30.seconds
                actual.retryRandomizationFactor shouldBe 0.0
            }

            "reads every key of the block" {
                // given
                val source =
                    reader(
                        key("connect-timeout-ms") to 1500,
                        key("request-timeout-ms") to 4500,
                        perAttempt,
                        key("max-attempts") to 7,
                        key("base-delay-ms") to 100,
                        key("randomization-factor") to 0.25,
                        key("retriable-statuses") to listOf(429, 503),
                    )

                // when
                val actual = HttpToolConfig.fromReader(source)

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
                val source = reader(perAttempt, "$TABLE.retry.max-attempts" to 9)

                // when
                val actual = HttpToolConfig.fromReader(source)

                // then
                actual.retryMaxAttempts shouldBe 5
            }

            "raises for the required key when the block writes every other key" {
                // given
                val source =
                    reader(
                        key("connect-timeout-ms") to 1500,
                        key("request-timeout-ms") to 4500,
                        key("max-attempts") to 7,
                        key("base-delay-ms") to 100,
                        key("randomization-factor") to 0.25,
                        key("retriable-statuses") to listOf(429, 503),
                    )

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "download.http-tool.per-attempt-timeout-ms is required"
            }

            "rejects a zero per attempt timeout" {
                // given
                val source = reader(key("per-attempt-timeout-ms") to 0)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "perAttemptTimeout must be positive, got 0s"
            }

            "rejects a whole number that cannot be read" {
                // given
                val source = reader(perAttempt, key("base-delay-ms") to "abc")

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "download.http-tool.base-delay-ms must be a whole number, got abc"
            }

            "rejects a whole number outside the int range" {
                // given
                val source = reader(perAttempt, key("max-attempts") to 5_000_000_000L)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe
                    "download.http-tool.max-attempts must be a whole number in the Int range, got 5000000000"
            }

            "rejects an attempt count below one" {
                // given
                val source = reader(perAttempt, key("max-attempts") to 0)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "retryMaxAttempts must be at least 1, got 0"
            }

            "rejects a non positive timeout" {
                // given
                val source = reader(perAttempt, key("request-timeout-ms") to 0)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "requestTimeout must be positive, got 0s"
            }

            "accepts a zero retry base delay, which repeats without waiting" {
                // given
                val source = reader(perAttempt, key("base-delay-ms") to 0)

                // when
                val actual = HttpToolConfig.fromReader(source)

                // then
                actual.retryBaseDelay shouldBe Duration.ZERO
            }

            "rejects a randomization factor outside the unit interval" {
                // given
                val source = reader(perAttempt, key("randomization-factor") to 2.0)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "retryRandomizationFactor must be within 0.0..1.0, got 2.0"
            }

            "rejects a status list that is not a list" {
                // given
                val source = reader(perAttempt, key("retriable-statuses") to 503)

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe
                    "download.http-tool.retriable-statuses must be a list of whole numbers, got 503"
            }

            "rejects a retriable status below one" {
                // given
                val source = reader(perAttempt, key("retriable-statuses") to listOf(0, 503))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { HttpToolConfig.fromReader(source) }

                // then
                thrown.message shouldBe "retryRetriableStatuses must be positive, got [0, 503]"
            }
        }

        "AppConfig" - {
            "carries the http tool block" {
                // given
                val source = reader("download.max-parallel-downloads" to 2, perAttempt, key("max-attempts") to 4)

                // when
                val actual = AppConfig.fromReader(source)

                // then
                actual.maxParallelDownloads shouldBe 2
                actual.httpTool.retryMaxAttempts shouldBe 4
            }

            "reads the block under its own key prefix" {
                // given
                val source =
                    reader(
                        "other.http-tool.per-attempt-timeout-ms" to 30000,
                        "other.http-tool.max-attempts" to 6,
                    )

                // when
                val actual = AppConfig.fromReader(source, keyPrefix = "other")

                // then
                actual.httpTool.retryMaxAttempts shouldBe 6
            }
        }
    })
