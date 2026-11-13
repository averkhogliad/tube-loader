package io.averkhogliad.tubeloader.adapters.youtube

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

private const val STUB = "stub"
private const val ASSET_NAME = "yt-dlp.exe"
private val ASSET = "asset-bytes".toByteArray()

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
                val failure = IOException("no network")
                val distribution = YtDlpDistribution(paths) { throw failure }

                // when
                val actual = runBlocking { distribution.ensureBinary(null) }

                // then
                actual.exceptionOrNull() shouldBe failure
                Files.exists(paths.ytDlpBinary) shouldBe false
            }
        }

        "fetchManaged" - {
            "fetches the asset through the redirects of the release and verifies its checksum" {
                // given
                val binary = root.resolve("redirected").resolve("yt-dlp")
                ReleaseServer().use { server ->
                    server.redirectAsset = true

                    // when
                    fetchManaged(binary, server.source())

                    // then
                    binary.readBytes().toList() shouldBe ASSET.toList()
                    Files.isExecutable(binary) shouldBe true
                }
                Files.list(binary.parent).use { entries -> entries.toList() } shouldBe listOf(binary)
            }

            "reports a checksum the source disagrees with and leaves no binary" {
                // given
                val binary = root.resolve("mismatch").resolve("yt-dlp")
                ReleaseServer().use { server ->
                    server.checksumBody = "${sha256Hex("other".toByteArray())}  $ASSET_NAME"

                    // when
                    val failure = shouldThrow<IOException> { fetchManaged(binary, server.source()) }

                    // then
                    failure.message!! shouldContain "checksum"
                }
                Files.exists(binary) shouldBe false
            }

            "reports a release that names no checksum of the asset and leaves no binary" {
                // given
                val binary = root.resolve("no-checksum").resolve("yt-dlp")
                ReleaseServer().use { server ->
                    server.checksumBody = "${sha256Hex(ASSET)}  another-binary"

                    // when
                    val failure = shouldThrow<IOException> { fetchManaged(binary, server.source()) }

                    // then
                    failure.message!! shouldContain "names no checksum"
                }
                Files.exists(binary) shouldBe false
            }

            "reports an asset the source refuses and leaves no binary" {
                // given
                val binary = root.resolve("refused").resolve("yt-dlp")
                ReleaseServer().use { server ->
                    server.assetStatus = 404

                    // when
                    val failure = shouldThrow<IOException> { fetchManaged(binary, server.source()) }

                    // then
                    failure.message!! shouldContain "server answered 404"
                }
                Files.exists(binary) shouldBe false
            }

            "reports a source that cannot be reached and leaves no binary" {
                // given
                val binary = root.resolve("unreachable").resolve("yt-dlp")
                val port = ServerSocket(0).use { socket -> socket.localPort }
                val source =
                    YtDlpSource(
                        binary = URI.create("http://127.0.0.1:$port/asset"),
                        checksums = URI.create("http://127.0.0.1:$port/sums"),
                        assetName = ASSET_NAME,
                    )

                // when
                val failure = shouldThrow<IOException> { fetchManaged(binary, source) }

                // then
                failure.message!! shouldContain "cannot download"
                Files.exists(binary) shouldBe false
            }
        }

        "YtDlpSource.current" - {
            "names the Windows asset of the latest release" {
                // when
                val actual = YtDlpSource.current("Windows 11")

                // then
                actual.assetName shouldBe ASSET_NAME
                actual.binary.toString() shouldContain ASSET_NAME
            }

            "reports a platform without a configured build as unavailable" {
                // when
                val failure = shouldThrow<IOException> { YtDlpSource.current("Linux") }

                // then
                failure.message!! shouldContain "LINUX"
            }

            "reports every platform it has no build for" {
                // when + then
                listOf("Mac OS X", "FreeBSD").forEach { osName ->
                    shouldThrow<IOException> { YtDlpSource.current(osName) }
                }
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

/**
 * A release host of its own: the asset, the sums and the redirect of the real source, served on
 * localhost. Nothing leaves the machine, and the redirect the fix follows is part of the fixture.
 */
private class ReleaseServer : Closeable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var assetStatus: Int = 200
    var checksumBody: String = "${sha256Hex(ASSET)}  $ASSET_NAME"
    var redirectAsset: Boolean = false

    init {
        server.createContext("/asset") { exchange ->
            if (redirectAsset) {
                exchange.responseHeaders.add("Location", "/release/asset")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                serveAsset(exchange)
            }
        }
        server.createContext("/release/asset") { exchange -> serveAsset(exchange) }
        server.createContext("/sums") { exchange ->
            val body = checksumBody.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { response -> response.write(body) }
        }
        server.start()
    }

    fun source(assetName: String = ASSET_NAME): YtDlpSource =
        YtDlpSource(binary = url("/asset"), checksums = url("/sums"), assetName = assetName)

    private fun serveAsset(exchange: HttpExchange) {
        exchange.sendResponseHeaders(assetStatus, if (assetStatus == 200) ASSET.size.toLong() else -1)
        if (assetStatus == 200) {
            exchange.responseBody.use { response -> response.write(ASSET) }
        }
        exchange.close()
    }

    private fun url(path: String): URI = URI.create("http://127.0.0.1:${server.address.port}$path")

    override fun close() {
        server.stop(0)
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
