package io.averkhogliad.tubeloader.core.config

import io.averkhogliad.tubeloader.config.ConfigReader
import io.averkhogliad.tubeloader.config.MemoryConfigReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val httpToolConfig = HttpToolConfig(perAttemptTimeout = 30.seconds)

private val perAttempt = "download.http-tool.per-attempt-timeout-ms" to 30000

private fun reader(vararg pairs: Pair<String, Any>): ConfigReader = MemoryConfigReader(pairs.toMap())

class AppConfigTest :
    FreeSpec({

        "fromReader" - {
            "returns defaults when the config has no download keys" {
                // given
                val source = reader(perAttempt)

                // when
                val actual = AppConfig.fromReader(source)

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
                val source =
                    reader(
                        "download.max-parallel-downloads" to "2",
                        "download.default-target-dir" to "D:/vid",
                        perAttempt,
                    )

                // when
                val actual = AppConfig.fromReader(source)

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
                val source = reader("download.max-parallel-downloads" to " 7 ", perAttempt)

                // when
                val actual = AppConfig.fromReader(source)

                // then
                actual.maxParallelDownloads shouldBe 7
            }

            "keeps one as the lowest accepted max-parallel-downloads" {
                // given
                val source = reader("download.max-parallel-downloads" to "1", perAttempt)

                // when
                val actual = AppConfig.fromReader(source)

                // then
                actual.maxParallelDownloads shouldBe 1
            }

            "falls back to default when max-parallel-downloads is zero or negative" {
                // given
                val zero = reader("download.max-parallel-downloads" to "0", perAttempt)
                val negative = reader("download.max-parallel-downloads" to "-3", perAttempt)

                // when
                val fromZero = AppConfig.fromReader(zero)
                val fromNegative = AppConfig.fromReader(negative)

                // then
                fromZero.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
                fromNegative.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
            }

            "falls back to default when max-parallel-downloads is not a number" {
                // given
                val source = reader("download.max-parallel-downloads" to "many", perAttempt)

                // when
                val actual = AppConfig.fromReader(source)

                // then
                actual.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
            }

            "ignores blank default-target-dir" {
                // given
                val source = reader("download.default-target-dir" to "   ", perAttempt)

                // when
                val actual = AppConfig.fromReader(source)

                // then
                actual.defaultTargetDir shouldBe null
            }

            "falls back to null when default-target-dir is an invalid path" {
                // given
                val source = reader("download.default-target-dir" to "a\u0000b", perAttempt)

                // when
                val actual = AppConfig.fromReader(source)

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
