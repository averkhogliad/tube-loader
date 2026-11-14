package io.averkhogliad.tubeloader.infra.binaries

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes

private const val TOOL = "tool"
private const val BINARY_NAME = "tool.exe"
private const val HTTP_OK = 200
private const val HTTP_FOUND = 302
private val BINARY = "binary-bytes".toByteArray()

class ManagedBinaryFetchTest :
    FreeSpec({

        lateinit var root: Path

        beforeSpec { root = Files.createTempDirectory("managed-binary-fetch") }

        afterSpec { deleteWithRetry(root) }

        "real fetch" - {
            "installs the tool of a zip archive after the checksum of the sidecar" {
                // given
                val archive = zipOf("tool-8/bin/$BINARY_NAME" to BINARY)
                ReleaseServer().use { server ->
                    server.archiveBody = archive
                    server.checksumBody = sha256Hex(archive)

                    // when
                    val actual = install(root.resolve("zip"), server.zipPolicy())

                    // then
                    actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
                    Files.isExecutable(actual.getOrThrow()) shouldBe true
                    filesOf(actual.getOrThrow().parent) shouldBe listOf(actual.getOrThrow())
                }
            }

            "takes the tool kept at the root of the archive" {
                // given
                val archive = zipOf(BINARY_NAME to BINARY)
                ReleaseServer().use { server ->
                    server.archiveBody = archive
                    server.checksumBody = sha256Hex(archive)

                    // when
                    val actual = install(root.resolve("root-entry"), server.zipPolicy())

                    // then
                    actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
                }
            }

            "installs the asset itself when the multi-asset list names it" {
                // given
                ReleaseServer().use { server ->
                    server.sumsBody = "${sha256Hex(BINARY)}  $BINARY_NAME"

                    // when
                    val actual = install(root.resolve("asset"), server.assetPolicy())

                    // then
                    actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
                }
            }

            "follows the redirects of the source when the policy asks" {
                // given
                ReleaseServer().use { server ->
                    server.sumsBody = "${sha256Hex(BINARY)}  $BINARY_NAME"

                    // when
                    val actual = install(root.resolve("redirected"), server.assetPolicy(redirect = true))

                    // then
                    actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
                }
            }

            "does not follow the redirects when the policy forbids" {
                // given
                ReleaseServer().use { server ->
                    val policy = server.zipPolicy(redirect = true).copy(followRedirects = false)

                    // when
                    val actual = install(root.resolve("not-followed"), policy)

                    // then
                    actual.exceptionOrNull()!!.message!! shouldContain "server answered $HTTP_FOUND"
                    Files.exists(root.resolve("not-followed").resolve(BINARY_NAME)) shouldBe false
                }
            }

            "reports a source that refuses the archive and leaves no binary" {
                // given
                ReleaseServer().use { server ->
                    server.archiveStatus = 404

                    // when
                    val actual = install(root.resolve("refused"), server.zipPolicy())

                    // then
                    actual.exceptionOrNull()!!.message!! shouldContain "server answered 404"
                    Files.exists(root.resolve("refused").resolve(BINARY_NAME)) shouldBe false
                }
            }

            "reports a release that names no checksum of the asset and leaves no binary" {
                // given
                ReleaseServer().use { server ->
                    server.sumsBody = "${sha256Hex(BINARY)}  another-binary"

                    // when
                    val actual = install(root.resolve("no-checksum"), server.assetPolicy())

                    // then
                    actual.exceptionOrNull()!!.message!! shouldContain "names no checksum"
                    Files.exists(root.resolve("no-checksum").resolve(BINARY_NAME)) shouldBe false
                }
            }

            "reports a checksum the source disagrees with and leaves no binary" {
                // given
                ReleaseServer().use { server ->
                    server.checksumBody = sha256Hex("other".toByteArray())

                    // when
                    val actual = install(root.resolve("mismatch"), server.zipPolicy())

                    // then
                    actual.exceptionOrNull()!!.message!! shouldContain "checksum"
                    Files.exists(root.resolve("mismatch").resolve(BINARY_NAME)) shouldBe false
                }
            }

            "reports a source that cannot be reached and leaves no binary" {
                // given
                val port = ServerSocket(0).use { socket -> socket.localPort }
                val policy =
                    ReleaseServer.policy(
                        source = URI.create("http://127.0.0.1:$port/archive"),
                        checksum = ChecksumSource.Sidecar(URI.create("http://127.0.0.1:$port/archive.sha256")),
                    )
                val target = root.resolve("unreachable").resolve(BINARY_NAME)

                // when
                val actual =
                    runBlocking { ManagedBinary(target, { policy }).ensureBinary(null) }

                // then
                actual.exceptionOrNull()!!.message!! shouldContain "cannot download"
                Files.exists(target) shouldBe false
            }
        }
    })

private fun install(directory: Path, policy: BinaryPolicy): Result<Path> =
    runBlocking { ManagedBinary(directory.resolve(BINARY_NAME), { policy }).ensureBinary(null) }

/**
 * A release host of its own: the archive, the asset, the sums and the redirects of the real source,
 * served on localhost. Nothing leaves the machine, and the response codes are part of the fixture.
 */
private class ReleaseServer : Closeable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var archiveStatus: Int = HTTP_OK
    var archiveBody: ByteArray = ByteArray(0)
    var checksumBody: String = ""
    var assetStatus: Int = HTTP_OK
    var sumsBody: String = ""

    init {
        server.createContext("/archive") { exchange -> serve(exchange, archiveStatus, archiveBody) }
        server.createContext("/archive-redirect") { exchange -> redirect(exchange, "/archive") }
        server.createContext("/archive.sha256") { exchange -> serve(exchange, HTTP_OK, checksumBody.toByteArray()) }
        server.createContext("/asset") { exchange -> serve(exchange, assetStatus, BINARY) }
        server.createContext("/asset-redirect") { exchange -> redirect(exchange, "/asset") }
        server.createContext("/sums") { exchange -> serve(exchange, HTTP_OK, sumsBody.toByteArray()) }
        server.start()
    }

    fun zipPolicy(redirect: Boolean = false): BinaryPolicy =
        policy(
            source = url(if (redirect) "/archive-redirect" else "/archive"),
            checksum = ChecksumSource.Sidecar(url("/archive.sha256")),
            followRedirects = redirect,
        )

    fun assetPolicy(redirect: Boolean = false): BinaryPolicy =
        policy(
            source = url(if (redirect) "/asset-redirect" else "/asset"),
            checksum = ChecksumSource.MultiAsset(url("/sums"), BINARY_NAME),
            followRedirects = redirect,
        ).copy(unpack = Unpack.ReadyFile)

    private fun serve(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, if (status == HTTP_OK) body.size.toLong() else -1)
        if (status == HTTP_OK) {
            exchange.responseBody.use { response -> response.write(body) }
        }
        exchange.close()
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(HTTP_FOUND, -1)
        exchange.close()
    }

    private fun url(path: String): URI = URI.create("http://127.0.0.1:${server.address.port}$path")

    override fun close() {
        server.stop(0)
    }

    companion object {

        fun policy(source: URI, checksum: ChecksumSource, followRedirects: Boolean = false): BinaryPolicy =
            BinaryPolicy(
                tool = TOOL,
                source = source,
                checksum = checksum,
                followRedirects = followRedirects,
                unpack = Unpack.ZipArchive,
            )
    }
}

private fun filesOf(directory: Path): List<Path> = Files.list(directory).use { entries -> entries.toList().sorted() }

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

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
