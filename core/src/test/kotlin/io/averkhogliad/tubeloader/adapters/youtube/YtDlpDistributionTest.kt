package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.deleteWithRetry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private const val STUB = "stub"

class YtDlpDistributionTest :
    FreeSpec({

        lateinit var root: Path

        beforeSpec { root = Files.createTempDirectory("yt-dlp-distribution") }

        afterSpec { deleteWithRetry(root) }

        "ensureBinary" - {
            "hands back the path the config injected and fetches nothing" {
                // given
                var fetches = 0
                val paths = ToolPaths.of(root.resolve("injected"), "Linux")
                val injected = root.resolve("bin").resolve("yt-dlp")

                // when
                val actual = runBlocking { YtDlpDistribution(paths) { fetches++ }.ensureBinary(injected) }

                // then
                actual.getOrNull() shouldBe injected
                fetches shouldBe 0
            }

            "hands back the binary already fetched into the home directory and fetches nothing" {
                // given
                var fetches = 0
                val paths = ToolPaths.of(root.resolve("reused"), "Linux")
                val managed = paths.ytDlpBinary.executable(STUB)

                // when
                val actual = runBlocking { YtDlpDistribution(paths) { fetches++ }.ensureBinary(null) }

                // then
                actual.getOrNull() shouldBe managed
                fetches shouldBe 0
            }

            "fetches the binary once when the home directory has none" {
                // given
                var fetches = 0
                val paths = ToolPaths.of(root.resolve("fetched"), "Linux")
                val distribution =
                    YtDlpDistribution(paths) { binary ->
                        fetches++
                        binary.executable(STUB)
                    }

                // when
                val first = runBlocking { distribution.ensureBinary(null) }
                val second = runBlocking { distribution.ensureBinary(null) }

                // then
                fetches shouldBe 1
                first.getOrNull() shouldBe paths.ytDlpBinary
                second.getOrNull() shouldBe paths.ytDlpBinary
            }

            "reports the failure of the fetch and leaves no binary behind" {
                // given
                val paths = ToolPaths.of(root.resolve("failed"), "Linux")
                val failure = java.io.IOException("no network")
                val distribution = YtDlpDistribution(paths) { throw failure }

                // when
                val actual = runBlocking { distribution.ensureBinary(null) }

                // then
                actual.exceptionOrNull() shouldBe failure
                Files.exists(paths.ytDlpBinary) shouldBe false
            }
        }

        "checksumOf" - {
            "finds the checksum of the asset among the sums of the release" {
                // given
                val sums =
                    """
                    aaa  yt-dlp
                    bbb  yt-dlp.exe
                    ccc  SHA2-256SUMS
                    """.trimIndent()

                // when
                val actual = checksumOf(sums, "yt-dlp.exe")

                // then
                actual shouldBe "bbb"
            }

            "names nothing when the release does not carry the asset" {
                // given
                val sums = "aaa  yt-dlp"

                // when
                val actual = checksumOf(sums, "yt-dlp.exe")

                // then
                actual shouldBe null
            }
        }
    })

/** Writes a stub binary where the distribution expects the managed one and leaves it executable. */
private fun Path.executable(content: String): Path {
    parent.toFile().mkdirs()
    writeText(content)
    toFile().setExecutable(true, false)
    return this
}
