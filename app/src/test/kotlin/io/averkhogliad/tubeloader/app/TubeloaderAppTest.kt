package io.averkhogliad.tubeloader.app

import io.averkhogliad.tubeloader.config.ConfigReader
import io.averkhogliad.tubeloader.config.MemoryConfigReader
import io.averkhogliad.tubeloader.config.TomlConfig
import io.averkhogliad.tubeloader.config.TomlConfigReader
import io.averkhogliad.tubeloader.core.config.AppConfig
import io.averkhogliad.tubeloader.core.config.MediaToolConfig
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val PER_ATTEMPT_TIMEOUT = "download.http-tool.per-attempt-timeout-ms"
private const val CONTAINER_FORMAT = "download.container-format"
private val HOME = Path.of("home")

private fun reader(vararg values: Pair<String, Any>, perAttemptMillis: Int = 60_000): MemoryConfigReader =
    MemoryConfigReader(mapOf<String, Any>(PER_ATTEMPT_TIMEOUT to perAttemptMillis) + values.toMap())

private fun <T> withApp(
    reader: ConfigReader,
    overrides: AppOverrides = AppOverrides(),
    block: (TubeloaderApp) -> T,
): T {
    val app = TubeloaderApp.build(reader, ToolPaths.of(HOME), overrides)
    return try {
        block(app)
    } finally {
        runBlocking { app.shutdown(5.seconds) }
        app.close()
    }
}

class TubeloaderAppTest :
    FreeSpec({

        "build" - {
            "keeps the values the config names when the frontend names none" {
                // given
                val source =
                    reader(
                        "download.max-parallel-downloads" to 2,
                        "download.default-target-dir" to "D:/config",
                    )

                // when
                val actual = withApp(source) { app -> app.config }

                // then
                actual.maxParallelDownloads shouldBe 2
                actual.defaultTargetDir shouldBe Path.of("D:/config")
            }

            "prefers the values of the frontend over the config" {
                // given
                val source =
                    reader(
                        "download.max-parallel-downloads" to 2,
                        "download.default-target-dir" to "D:/config",
                    )
                val overrides = AppOverrides(maxParallelDownloads = 5, defaultTargetDir = Path.of("D:/frontend"))

                // when
                val actual = withApp(source, overrides) { app -> app.config }

                // then
                actual.maxParallelDownloads shouldBe 5
                actual.defaultTargetDir shouldBe Path.of("D:/frontend")
            }

            "falls back to the defaults when neither the frontend nor the config names a value" {
                // when
                val actual = withApp(reader()) { app -> app.config }

                // then
                actual.maxParallelDownloads shouldBe AppConfig.DEFAULT_MAX_PARALLEL_DOWNLOADS
                actual.defaultTargetDir.shouldBeNull()
            }

            "reads the http tool block of the config" {
                // given
                val source =
                    reader(
                        "download.http-tool.connect-timeout-ms" to 1500,
                        "download.http-tool.max-attempts" to 7,
                        perAttemptMillis = 1234,
                    )

                // when
                val actual = withApp(source) { app -> app.config.httpTool }

                // then
                actual.connectTimeout shouldBe 1500.milliseconds
                actual.perAttemptTimeout shouldBe 1234.milliseconds
                actual.retryMaxAttempts shouldBe 7
            }

            "reads the same keys from a TOML source" {
                // given
                val toml =
                    TomlConfig.fromString(
                        """
                        [download]
                        max-parallel-downloads = 4
                        default-target-dir = "D:/toml"
                        container-format = "mkv"

                        [download.http-tool]
                        per-attempt-timeout-ms = 2500
                        """.trimIndent(),
                    )

                // when
                val actual = withApp(TomlConfigReader(toml)) { app -> app.config }

                // then
                actual.maxParallelDownloads shouldBe 4
                actual.defaultTargetDir shouldBe Path.of("D:/toml")
                actual.httpTool.perAttemptTimeout shouldBe 2500.milliseconds
            }
        }

        "the graph" - {
            "serves the sources of the application" {
                // when
                val actual = withApp(reader()) { app -> app.sources.map { source -> source.code } }

                // then
                actual shouldBe listOf("rutube", "youtube")
            }

            "hands the same sources to the resolution of the media" {
                // when + then
                withApp(reader()) { app -> app.resolveMedia.sources shouldBe app.sources }
            }

            "starts the session of downloads empty" {
                // when
                val actual = withApp(reader()) { app -> runBlocking { app.downloads.states().first() } }

                // then
                actual shouldBe emptyList()
            }

            "stops the queue within the budget" {
                // when
                val actual = withApp(reader()) { app -> runBlocking { app.shutdown(5.seconds) } }

                // then
                actual shouldBe true
            }
        }

        "containerFormat" - {
            "reads the container of the download block" {
                // when
                val actual = containerFormat(reader(CONTAINER_FORMAT to "mkv"))

                // then
                actual shouldBe "mkv"
            }

            "trims the value" {
                // when
                val actual = containerFormat(reader(CONTAINER_FORMAT to "  webm "))

                // then
                actual shouldBe "webm"
            }

            "reads the container of a TOML source" {
                // given
                val toml = TomlConfig.fromString("[download]\ncontainer-format = \"mov\"")

                // when
                val actual = containerFormat(TomlConfigReader(toml))

                // then
                actual shouldBe "mov"
            }

            "falls back to the default when the config names none" {
                // when
                val actual = containerFormat(reader())

                // then
                actual shouldBe MediaToolConfig.DEFAULT_CONTAINER_FORMAT
            }

            "falls back to the default when the value is blank" {
                // when
                val actual = containerFormat(reader(CONTAINER_FORMAT to "   "))

                // then
                actual shouldBe MediaToolConfig.DEFAULT_CONTAINER_FORMAT
            }
        }
    })
