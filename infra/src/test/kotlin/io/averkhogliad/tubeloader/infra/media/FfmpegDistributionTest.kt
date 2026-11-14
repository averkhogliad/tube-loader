package io.averkhogliad.tubeloader.infra.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

private const val STUB = "stub"
private const val HTTP_OK = 200
private val BINARY = "binary-bytes".toByteArray()

class FfmpegDistributionTest :
    FreeSpec({

        lateinit var root: Path

        beforeSpec { root = Files.createTempDirectory("ffmpeg-distribution") }

        afterSpec { deleteWithRetry(root) }

        "ensureManagedFfmpeg" - {
            "hands back the binary already unpacked in the home directory and fetches nothing" {
                // given
                val paths = ToolPaths.of(root.resolve("managed"))
                val managed = paths.ffmpegBinary.executable(STUB)

                // when
                val actual = ensureManagedFfmpeg(paths)

                // then
                actual.getOrNull() shouldBe managed
            }
        }

        "ensureBinary" - {
            "hands back the path the config injected and fetches nothing" {
                // given
                var fetches = 0
                val paths = ToolPaths.of(root.resolve("injected"), "Linux")
                val injected = root.resolve("bin").resolve("ffmpeg")

                // when
                val actual = FfmpegDistribution(paths) { fetches++ }.ensureBinary(injected)

                // then
                actual.getOrNull() shouldBe injected
                fetches shouldBe 0
            }

            "hands back the binary already unpacked in the home directory and fetches nothing" {
                // given
                var fetches = 0
                val paths = ToolPaths.of(root.resolve("reused"), "Linux")
                val managed = paths.ffmpegBinary.executable(STUB)

                // when
                val actual = FfmpegDistribution(paths) { fetches++ }.ensureBinary(null)

                // then
                actual.getOrNull() shouldBe managed
                fetches shouldBe 0
            }

            "fetches the binary once when the home directory has none" {
                // given
                var fetches = 0
                val paths = ToolPaths.of(root.resolve("fetched"), "Linux")
                val distribution =
                    FfmpegDistribution(paths) { binary ->
                        fetches++
                        binary.executable(STUB)
                    }

                // when
                val first = distribution.ensureBinary(null)
                val second = distribution.ensureBinary(null)

                // then
                fetches shouldBe 1
                first.getOrNull() shouldBe paths.ffmpegBinary
                second.getOrNull() shouldBe paths.ffmpegBinary
            }

            "reports the failure of the fetch and leaves no binary behind" {
                // given
                val paths = ToolPaths.of(root.resolve("failed"), "Linux")
                val failure = IOException("no network")
                val distribution = FfmpegDistribution(paths) { throw failure }

                // when
                val actual = distribution.ensureBinary(null)

                // then
                actual.exceptionOrNull() shouldBe failure
                Files.exists(paths.ffmpegBinary) shouldBe false
            }
        }

        "fetchManaged" - {
            "fetches the tool from the archive of the source and verifies its checksum" {
                // given
                val binary = ToolPaths.of(root.resolve("nested")).ffmpegBinary
                ReleaseServer().use { server ->
                    server.archiveBody = zipOf("ffmpeg-8/bin/${binary.fileName}" to BINARY)
                    server.checksumBody = sha256Hex(server.archiveBody)

                    // when
                    fetchManaged(binary, server.source())

                    // then
                    binary.readBytes().toList() shouldBe BINARY.toList()
                    Files.isExecutable(binary) shouldBe true
                }
                filesOf(binary.parent) shouldBe listOf(binary)
            }

            "takes the tool kept at the root of the archive" {
                // given
                val binary = ToolPaths.of(root.resolve("root-entry")).ffmpegBinary
                ReleaseServer().use { server ->
                    server.archiveBody = zipOf(binary.fileName.toString() to BINARY)
                    server.checksumBody = sha256Hex(server.archiveBody)

                    // when
                    fetchManaged(binary, server.source())

                    // then
                    binary.readBytes().toList() shouldBe BINARY.toList()
                }
            }

            "reports a checksum the source disagrees with and leaves no binary" {
                // given
                val binary = ToolPaths.of(root.resolve("mismatch")).ffmpegBinary
                ReleaseServer().use { server ->
                    server.archiveBody = zipOf("ffmpeg-8/bin/${binary.fileName}" to BINARY)
                    server.checksumBody = sha256Hex("other".toByteArray())

                    // when
                    val failure = shouldThrow<IOException> { fetchManaged(binary, server.source()) }

                    // then
                    failure.message!! shouldContain "checksum"
                }
                Files.exists(binary) shouldBe false
            }

            "reports an archive without the tool and leaves no binary" {
                // given
                val binary = ToolPaths.of(root.resolve("no-binary")).ffmpegBinary
                ReleaseServer().use { server ->
                    server.archiveBody = zipOf("ffmpeg-8/README" to BINARY)
                    server.checksumBody = sha256Hex(server.archiveBody)

                    // when
                    val failure = shouldThrow<IOException> { fetchManaged(binary, server.source()) }

                    // then
                    failure.message!! shouldContain "holds no ${binary.fileName}"
                }
                Files.exists(binary) shouldBe false
            }

            "reports an archive the source refuses and leaves no binary" {
                // given
                val binary = ToolPaths.of(root.resolve("refused")).ffmpegBinary
                ReleaseServer().use { server ->
                    server.archiveStatus = 404
                    server.checksumBody = sha256Hex(BINARY)

                    // when
                    val failure = shouldThrow<IOException> { fetchManaged(binary, server.source()) }

                    // then
                    failure.message!! shouldContain "server answered 404"
                }
                Files.exists(binary) shouldBe false
            }

            "reports a source that cannot be reached and leaves no binary" {
                // given
                val binary = ToolPaths.of(root.resolve("unreachable")).ffmpegBinary
                val port = ServerSocket(0).use { socket -> socket.localPort }
                val source = FfmpegSource(URI.create("http://127.0.0.1:$port/archive"), ".zip")

                // when
                val failure = shouldThrow<IOException> { fetchManaged(binary, source) }

                // then
                failure.message!! shouldContain "cannot download"
                Files.exists(binary) shouldBe false
            }
        }

        "FfmpegSource.current" - {
            "names the Windows archive of the official build" {
                // when
                val actual = FfmpegSource.current("Windows 11")

                // then
                actual.archiveSuffix shouldBe ".zip"
                actual.archive.toString() shouldContain "ffmpeg-"
            }

            "reports a platform without a configured build as unavailable" {
                // when
                val failure = shouldThrow<IOException> { FfmpegSource.current("Linux") }

                // then
                failure.message!! shouldContain "LINUX"
            }

            "reports every platform it has no build for" {
                // when + then
                listOf("Mac OS X", "FreeBSD").forEach { osName ->
                    shouldThrow<IOException> { FfmpegSource.current(osName) }
                }
            }
        }
    })

/** Writes a stub binary where the distribution expects the managed one and leaves it executable. */
private fun Path.executable(content: String): Path {
    parent.createDirectories()
    writeText(content)
    toFile().setExecutable(true, false)
    return this
}

private fun filesOf(dir: Path): List<Path> = Files.list(dir).use { entries -> entries.toList().sorted() }

private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * A release host of its own: the archive and its checksum, served on localhost. Nothing leaves the
 * machine, and the response codes of the source are part of the fixture.
 */
private class ReleaseServer : Closeable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var archiveStatus: Int = HTTP_OK
    var archiveBody: ByteArray = ByteArray(0)
    var checksumBody: String = ""

    init {
        server.createContext("/archive") { exchange -> serve(exchange, archiveStatus, archiveBody) }
        server.createContext("/archive.sha256") { exchange -> serve(exchange, HTTP_OK, checksumBody.toByteArray()) }
        server.start()
    }

    fun source(): FfmpegSource = FfmpegSource(url("/archive"), ".zip")

    private fun serve(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, if (status == HTTP_OK) body.size.toLong() else -1)
        if (status == HTTP_OK) {
            exchange.responseBody.use { response -> response.write(body) }
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
