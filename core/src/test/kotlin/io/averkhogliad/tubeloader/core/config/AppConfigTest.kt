package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.mapConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val httpToolConfig = HttpToolConfig(perAttemptTimeout = 30.seconds)

private val perAttempt = "download.http-tool.per-attempt-timeout-ms" to "30000"

class AppConfigTest :
    FreeSpec({

        "fromConfig" - {
            "returns defaults when the config has no download keys" {
                // given
                val config = mapConfig(perAttempt)

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual shouldBe
                    AppConfig(
                        maxParallelDownloads = AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS,
                        defaultTargetDir = null,
                        httpTool = httpToolConfig,
                    )
            }

            "overrides values from the config" {
                // given
                val config =
                    mapConfig(
                        "download.max-parallel-downloads" to "2",
                        "download.default-target-dir" to "D:/vid",
                        perAttempt,
                    )

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual shouldBe
                    AppConfig(
                        maxParallelDownloads = 2,
                        defaultTargetDir = Path.of("D:/vid"),
                        httpTool = httpToolConfig,
                    )
            }

            "trims surrounding whitespace around max-parallel-downloads" {
                // given
                val config = mapConfig("download.max-parallel-downloads" to " 7 ", perAttempt)

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual.maxParallelDownloads shouldBe 7
            }

            "keeps one as the lowest accepted max-parallel-downloads" {
                // given
                val config = mapConfig("download.max-parallel-downloads" to "1", perAttempt)

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual.maxParallelDownloads shouldBe 1
            }

            "falls back to default when max-parallel-downloads is zero or negative" {
                // given
                val zero = mapConfig("download.max-parallel-downloads" to "0", perAttempt)
                val negative = mapConfig("download.max-parallel-downloads" to "-3", perAttempt)

                // when
                val fromZero = AppConfig.fromConfig(zero)
                val fromNegative = AppConfig.fromConfig(negative)

                // then
                fromZero.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
                fromNegative.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
            }

            "falls back to default when max-parallel-downloads is not a number" {
                // given
                val config = mapConfig("download.max-parallel-downloads" to "many", perAttempt)

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
            }

            "ignores blank default-target-dir" {
                // given
                val config = mapConfig("download.default-target-dir" to "   ", perAttempt)

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual.defaultTargetDir shouldBe null
            }

            "falls back to null when default-target-dir is an invalid path" {
                // given
                val config = mapConfig("download.default-target-dir" to "a\u0000b", perAttempt)

                // when
                val actual = AppConfig.fromConfig(config)

                // then
                actual.defaultTargetDir shouldBe null
            }
        }

        "constructor" - {
            "rejects maxParallelDownloads below one" {
                shouldThrow<IllegalArgumentException> {
                    AppConfig(maxParallelDownloads = 0, httpTool = httpToolConfig)
                }
            }

            "accepts single-threaded limit" {
                AppConfig(maxParallelDownloads = 1, httpTool = httpToolConfig).maxParallelDownloads shouldBe 1
            }
        }
    })
