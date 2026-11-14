package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.averkhogliad.tubeloader.infra.binaries.BinaryFetch
import io.averkhogliad.tubeloader.infra.binaries.BinaryPolicy
import io.averkhogliad.tubeloader.infra.binaries.ChecksumSource
import io.averkhogliad.tubeloader.infra.binaries.ManagedBinary
import io.averkhogliad.tubeloader.infra.binaries.Unpack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
private const val SUMS_ASSET = "SHA2-256SUMS"
private val BINARY = "binary-bytes".toByteArray()

/** One release of the source: the platform the JVM sees, its architecture and the asset it names. */
private data class Platform(
    val osName: String,
    val arch: String,
    val asset: String,
)

class YtDlpPolicyTest :
    FreeSpec({
        val roots = mutableListOf<Path>()

        afterSpec { roots.forEach { deleteWithRetry(it) } }

        "ytDlpPolicy" - {
            "names the Windows asset of the latest release with the multi-asset checksum" {
                // when
                val actual = ytDlpPolicy("Windows 11", "amd64")

                // then
                actual.shouldName("yt-dlp.exe")
            }

            "names the x86-64 Linux asset for the amd64 architecture" {
                // when
                val actual = ytDlpPolicy("Linux", "amd64")

                // then
                actual.shouldName("yt-dlp_linux")
            }

            "names the x86-64 Linux asset for the x86_64 architecture" {
                // when
                val actual = ytDlpPolicy("Linux", "x86_64")

                // then
                actual.shouldName("yt-dlp_linux")
            }

            "names the aarch64 Linux asset for both names of the architecture" {
                // when + then
                listOf("aarch64", "arm64").forEach { arch ->
                    ytDlpPolicy("Linux", arch).shouldName("yt-dlp_linux_aarch64")
                }
            }

            "names the universal macOS asset for both architectures" {
                // when + then
                listOf("x86_64", "aarch64").forEach { arch ->
                    ytDlpPolicy("Mac OS X", arch).shouldName("yt-dlp_macos")
                }
            }

            "installs the platform build as the managed binary of the home directory" {
                // given
                val platforms =
                    listOf(
                        Platform("Linux", "amd64", "yt-dlp_linux"),
                        Platform("Linux", "aarch64", "yt-dlp_linux_aarch64"),
                        Platform("Mac OS X", "aarch64", "yt-dlp_macos"),
                    )

                // when + then
                platforms.forEach { platform ->
                    val home = Files.createTempDirectory("yt-dlp-policy").also(roots::add)
                    val paths = ToolPaths.of(home, platform.osName)
                    val fetch = ReleaseFetch(platform.asset)
                    val actual =
                        runBlocking {
                            ManagedBinary(paths.ytDlpBinary, { ytDlpPolicy(platform.osName, platform.arch) }, fetch)
                                .ensureBinary(null)
                        }

                    actual.getOrThrow() shouldBe home.resolve(ToolPaths.YT_DLP_DIR).resolve("yt-dlp")
                    Files.readAllBytes(actual.getOrThrow()).toList() shouldBe BINARY.toList()
                    fetch.requested shouldBe listOf(platform.asset, SUMS_ASSET)
                }
            }

            "reports a platform without a configured build as unavailable" {
                // when
                val failure = shouldThrow<IOException> { ytDlpPolicy("FreeBSD", "amd64") }

                // then
                failure.message!! shouldContain "yt-dlp"
                failure.message!! shouldContain "OTHER"
            }

            "reports a Linux architecture without a published build as unavailable" {
                // when
                val failure = shouldThrow<IOException> { ytDlpPolicy("Linux", "armv7l") }

                // then
                failure.message!! shouldContain "yt-dlp"
                failure.message!! shouldContain "LINUX"
                failure.message!! shouldContain "armv7l"
            }
        }
    })

/** The release host as a seam: serves [asset] by the body the policy names and the sums of the release. */
private class ReleaseFetch(private val asset: String) : BinaryFetch {

    val requested: MutableList<String> = mutableListOf()

    override suspend fun download(tool: String, source: URI, target: Path, followRedirects: Boolean) {
        requested += source.toString().substringAfterLast('/')
        Files.write(target, BINARY)
    }

    override suspend fun body(tool: String, source: URI, followRedirects: Boolean): String {
        requested += source.toString().substringAfterLast('/')
        return "${sha256Hex(BINARY)}  $asset"
    }
}

private fun BinaryPolicy.shouldName(asset: String) {
    tool shouldBe "yt-dlp"
    source shouldBe URI.create(RELEASE + asset)
    checksum shouldBe ChecksumSource.MultiAsset(URI.create(RELEASE + SUMS_ASSET), asset)
    followRedirects shouldBe true
    unpack shouldBe Unpack.ReadyFile
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
