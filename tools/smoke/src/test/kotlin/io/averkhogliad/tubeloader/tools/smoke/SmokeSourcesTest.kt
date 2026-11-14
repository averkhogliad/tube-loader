package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.app.TubeloaderApp
import io.averkhogliad.tubeloader.config.MemoryConfigReader
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val PER_ATTEMPT_TIMEOUT = "download.http-tool.per-attempt-timeout-ms"
private val HOME = Path.of("home")

/**
 * The graph of the tool, built by the composition root as the run builds it: the codes of the
 * sources are the ones the tool registers, not a copy of them.
 */
private fun <T> withApp(block: (TubeloaderApp) -> T): T {
    val app =
        TubeloaderApp.build(
            MemoryConfigReader(mapOf(PER_ATTEMPT_TIMEOUT to 60_000)),
            ToolPaths.of(HOME),
        )
    return try {
        block(app)
    } finally {
        runBlocking { app.shutdown(5.seconds) }
        app.close()
    }
}

class SmokeSourcesTest :
    FreeSpec({

        "the graph of the run" - {
            "holds the adapter of every source the tool knows, under its code" {
                // when
                val codes = withApp { app -> app.sources.map { source -> source.code } }

                // then
                codes shouldBe listOf("rutube", "youtube")
            }

            "names the source of every adapter" {
                // when
                val names = withApp { app -> app.sources.map { source -> source.displayName } }

                // then
                names shouldBe listOf("Rutube", "YouTube")
            }
        }

        "requireKnownSourceCodes" - {
            "passes when every code of the targets is known" {
                // given
                val targets = listOf(SmokeTarget.ById("rutube", "abc", "clip"))

                // when + then
                withApp { app -> requireKnownSourceCodes(targets, app.sources.map { it.code }) }
            }

            "passes when the targets name no source at all" {
                // given
                val targets = listOf(SmokeTarget.ByUrl("https://rutube.ru/video/abc/", "clip"))

                // when + then
                withApp { app -> requireKnownSourceCodes(targets, app.sources.map { it.code }) }
            }

            "fails with the list of the known codes when a code is unknown" {
                // given
                val targets = listOf(SmokeTarget.ById("nowhere", "abc", "clip"))

                // when
                val failure =
                    shouldThrow<IllegalStateException> {
                        withApp { app ->
                            requireKnownSourceCodes(targets, app.sources.map { it.code })
                        }
                    }

                // then
                failure.message!! shouldContain "nowhere"
                failure.message!! shouldContain "rutube"
                failure.message!! shouldContain "youtube"
            }
        }
    })
