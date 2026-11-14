package io.averkhogliad.tubeloader.infra.binaries

import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private const val TOOL = "tool"
private const val BINARY_NAME = "tool.exe"
private val BINARY = "binary-bytes".toByteArray()

class ManagedBinaryTest :
    FreeSpec({

        lateinit var root: Path

        beforeSpec { root = Files.createTempDirectory("managed-binary") }

        afterSpec { deleteWithRetry(root) }

        "ensureBinary" - {
            "hands back the path the config injected and fetches nothing" {
                // given
                val world = UnderTest(root.resolve("injected"))
                val injected = root.resolve("bin").resolve(BINARY_NAME)

                // when
                val actual = runBlocking { world.binary.ensureBinary(injected) }

                // then
                actual.getOrNull() shouldBe injected
                world.fetch.downloads shouldBe 0
            }

            "hands back the binary already installed and fetches nothing" {
                // given
                val world = UnderTest(root.resolve("reused"))
                world.target.executable()

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.getOrNull() shouldBe world.target
                world.fetch.downloads shouldBe 0
            }

            "fetches the binary once when the home directory has none" {
                // given
                val world = UnderTest(root.resolve("fetched"))

                // when
                val first = runBlocking { world.binary.ensureBinary(null) }
                val second = runBlocking { world.binary.ensureBinary(null) }

                // then
                world.fetch.downloads shouldBe 1
                first.getOrNull() shouldBe world.target
                second.getOrNull() shouldBe world.target
            }

            "fetches once when concurrent calls find no binary" {
                // given
                val world = UnderTest(root.resolve("concurrent"), FakeFetch(delayMillis = 50))

                // when
                val (first, second) =
                    runBlocking {
                        coroutineScope {
                            val one = async { world.binary.ensureBinary(null) }
                            val two = async { world.binary.ensureBinary(null) }
                            one.await() to two.await()
                        }
                    }

                // then
                world.fetch.downloads shouldBe 1
                first.getOrNull() shouldBe world.target
                second.getOrNull() shouldBe world.target
            }

            "reports the failure of the fetch and leaves no binary behind" {
                // given
                val failure = IOException("no network")
                val world = UnderTest(root.resolve("failed"), FakeFetch(failure = failure))

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.exceptionOrNull() shouldBe failure
                Files.exists(world.target) shouldBe false
            }

            "leaves no partial file when the fetch fails after writing" {
                // given
                val world = UnderTest(root.resolve("partial"), FakeFetch(failure = IOException("no network")))

                // when
                runBlocking { world.binary.ensureBinary(null) }

                // then
                Files.exists(world.target) shouldBe false
                filesOf(world.target.parent).shouldBeEmpty()
            }

            "never writes the target before the binary is installed" {
                // given
                var targetDuringFetch: Boolean? = null
                lateinit var world: UnderTest
                world =
                    UnderTest(
                        root.resolve("atomic"),
                        FakeFetch(onDownload = { targetDuringFetch = Files.exists(world.target) }),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                targetDuringFetch shouldBe false
                Files.exists(actual.getOrThrow()) shouldBe true
            }

            "marks the installed binary executable" {
                // given
                val world = UnderTest(root.resolve("executable"))

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                Files.isExecutable(world.target) shouldBe true
                actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
            }

            "reports a platform without a configured build" {
                // given
                val target = root.resolve("platform").resolve(BINARY_NAME)
                val binary = ManagedBinary(target, policy = { noBuildFor(TOOL, Os.LINUX) })

                // when
                val actual = runBlocking { binary.ensureBinary(null) }

                // then
                actual.exceptionOrNull()!!.message!! shouldContain "LINUX"
                actual.exceptionOrNull()!!.message!! shouldContain TOOL
                Files.exists(target) shouldBe false
            }
        }

        "checksum" - {
            "reports a checksum the source disagrees with and leaves no binary" {
                // given
                val world =
                    UnderTest(
                        root.resolve("mismatch"),
                        FakeFetch(body = sha256Hex("other".toByteArray())),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.exceptionOrNull()!!.message!! shouldContain "checksum"
                Files.exists(world.target) shouldBe false
            }

            "takes the checksum of the asset from the multi-asset list" {
                // given
                val world =
                    UnderTest(
                        root.resolve("multi-asset"),
                        FakeFetch(body = "${sha256Hex(BINARY)}  $BINARY_NAME"),
                        multiAssetPolicy(),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                Files.exists(actual.getOrThrow()) shouldBe true
            }

            "reports a source that names no checksum of the asset and leaves no binary" {
                // given
                val world =
                    UnderTest(
                        root.resolve("no-checksum"),
                        FakeFetch(body = "${sha256Hex(BINARY)}  another-binary"),
                        multiAssetPolicy(),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.exceptionOrNull()!!.message!! shouldContain "names no checksum"
                Files.exists(world.target) shouldBe false
            }
        }

        "archive" - {
            "takes the tool from the bin entry of the archive" {
                // given
                val archive = zipOf("tool-8/bin/$BINARY_NAME" to BINARY)
                val world =
                    UnderTest(
                        root.resolve("nested"),
                        FakeFetch(body = sha256Hex(archive), content = archive),
                        zipPolicy(),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
                filesOf(world.target.parent) shouldBe listOf(world.target)
            }

            "takes the tool kept at the root of the archive" {
                // given
                val archive = zipOf(BINARY_NAME to BINARY)
                val world =
                    UnderTest(
                        root.resolve("root-entry"),
                        FakeFetch(body = sha256Hex(archive), content = archive),
                        zipPolicy(),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.getOrThrow().readBytes().toList() shouldBe BINARY.toList()
            }

            "reports an archive without the tool and leaves no binary" {
                // given
                val archive = zipOf("README" to BINARY)
                val world =
                    UnderTest(
                        root.resolve("no-binary"),
                        FakeFetch(body = sha256Hex(archive), content = archive),
                        zipPolicy(),
                    )

                // when
                val actual = runBlocking { world.binary.ensureBinary(null) }

                // then
                actual.exceptionOrNull()!!.message!! shouldContain "holds no $BINARY_NAME"
                Files.exists(world.target) shouldBe false
                filesOf(world.target.parent).shouldBeEmpty()
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

private class UnderTest(
    private val directory: Path,
    val fetch: FakeFetch = FakeFetch(),
    policy: BinaryPolicy = sidecarPolicy(),
) {

    val target: Path = directory.resolve(BINARY_NAME)
    val binary: ManagedBinary = ManagedBinary(target, { policy }, fetch)
}

private fun sidecarPolicy(): BinaryPolicy =
    BinaryPolicy(
        tool = TOOL,
        source = URI.create("https://example.test/tool"),
        checksum = ChecksumSource.Sidecar(URI.create("https://example.test/tool.sha256")),
        followRedirects = false,
        unpack = Unpack.ReadyFile,
    )

private fun multiAssetPolicy(): BinaryPolicy =
    sidecarPolicy().copy(checksum = ChecksumSource.MultiAsset(URI.create("https://example.test/sums"), BINARY_NAME))

private fun zipPolicy(): BinaryPolicy = sidecarPolicy().copy(unpack = Unpack.ZipArchive)

private class FakeFetch(
    private val body: String = sha256Hex(BINARY),
    private val content: ByteArray = BINARY,
    private val failure: Throwable? = null,
    private val delayMillis: Long = 0,
    private val onDownload: (Path) -> Unit = {},
) : BinaryFetch {

    var downloads: Int = 0
        private set

    override suspend fun download(tool: String, source: URI, target: Path, followRedirects: Boolean) {
        downloads++
        if (delayMillis > 0) delay(delayMillis)
        failure?.let { throw it }
        target.writeBytes(content)
        onDownload(target)
    }

    override suspend fun body(tool: String, source: URI, followRedirects: Boolean): String = body
}

/** Writes a stub binary where the distribution expects the managed one and leaves it executable. */
private fun Path.executable(): Path {
    parent.createDirectories()
    writeText("stub")
    toFile().setExecutable(true, false)
    return this
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
