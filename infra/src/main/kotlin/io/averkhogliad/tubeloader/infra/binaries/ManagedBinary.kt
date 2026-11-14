package io.averkhogliad.tubeloader.infra.binaries

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Delivers the build of an external tool into the home directory of the application: downloads it by
 * the policy of the tool, verifies the checksum, installs it atomically (partial in the target
 * directory, then an atomic move), marks it executable and reuses the file already there.
 *
 * One instance owns one tool and one mutex: concurrent calls of the same instance share a single
 * fetch, and the file appears whole or not at all. The instance belongs to the composition root, not
 * to a call.
 *
 * [policy] is data of the tool, built next to it; [fetch] is the network seam, so a test drives the
 * mechanism without reaching the network.
 */
class ManagedBinary(
    private val binary: Path,
    private val policy: () -> BinaryPolicy,
    private val fetch: BinaryFetch = HttpBinaryFetch(),
) {

    private val mutex = Mutex()

    /**
     * @param override a path the config gives instead of the managed one: used as is, nothing is
     *   downloaded and nothing is checked.
     * @return the binary to run, or the reason it could not be obtained.
     */
    suspend fun ensureBinary(override: Path?): Result<Path> =
        override?.let { path -> Result.success(path) } ?: ensureManaged()

    private suspend fun ensureManaged(): Result<Path> =
        mutex.withLock {
            // a parallel call may have fetched the file while this one waited for the lock
            if (Files.isExecutable(binary)) {
                Result.success(binary)
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        install()
                        Result.success(binary)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: IOException) {
                        Result.failure(failure)
                    }
                }
            }
        }

    private suspend fun install() {
        val settings = policy()
        val directory = requireNotNull(binary.parent) { "the binary path has no directory: $binary" }
        directory.createDirectories()
        val archive = Files.createTempFile(directory, binary.fileName.toString(), PARTIAL_SUFFIX)
        var installed: Path? = null
        try {
            fetch.download(settings.tool, settings.source, archive, settings.followRedirects)
            verifyChecksum(settings, archive)
            installed = unpack(settings, archive, directory)
            moveIntoPlace(installed, binary)
            markExecutable(binary)
        } finally {
            Files.deleteIfExists(archive)
            installed
                ?.takeIf { path -> path != archive }
                ?.let(Files::deleteIfExists)
        }
    }

    private suspend fun verifyChecksum(settings: BinaryPolicy, archive: Path) {
        val expected =
            when (val checksum = settings.checksum) {
                is ChecksumSource.Sidecar -> {
                    sidecarChecksum(fetch.body(settings.tool, checksum.source, settings.followRedirects))
                }

                is ChecksumSource.MultiAsset -> {
                    val sums = fetch.body(settings.tool, checksum.source, settings.followRedirects)
                    checksumOf(sums, checksum.asset)
                        ?: throw NoChecksumInSource(settings.tool, checksum.asset, checksum.source)
                }
            }
        val actual = sha256(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ChecksumMismatch(settings.tool, expected, actual)
        }
    }

    private fun unpack(settings: BinaryPolicy, archive: Path, directory: Path): Path =
        when (settings.unpack) {
            Unpack.ReadyFile -> archive
            Unpack.ZipArchive -> extract(settings.tool, archive, directory)
        }

    /** Writes the tool of [archive] to a partial next to the target; the archive keeps it under `bin/`. */
    private fun extract(tool: String, archive: Path, directory: Path): Path {
        val name = binary.fileName.toString()
        val partial = Files.createTempFile(directory, name, PARTIAL_SUFFIX)
        var extracted = false
        try {
            ZipInputStream(archive.inputStream()).use { zip ->
                zip.advanceTo(tool, name)
                partial.outputStream().use { output -> zip.copyTo(output) }
                zip.closeEntry()
            }
            extracted = true
        } finally {
            if (!extracted) Files.deleteIfExists(partial)
        }
        return partial
    }

    /** Leaves the stream on the body of the entry holding the tool of the source. */
    private fun ZipInputStream.advanceTo(tool: String, name: String) {
        generateSequence { nextEntry }
            .firstOrNull { entry -> entry.name.endsWith("/bin/$name") || entry.name == name }
            ?: throw NoBinaryInArchive(tool, name)
    }

    private fun moveIntoPlace(partial: Path, target: Path) {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {

        const val PARTIAL_SUFFIX = ".part"

        /**
         * A file written from the network carries no execution bit; on POSIX the tool would not start
         * and the next call would fetch it again. Windows has no such bit and does not need the call.
         */
        fun markExecutable(binary: Path) {
            runCatching {
                val permissions = Files.getPosixFilePermissions(binary).toMutableSet()
                permissions += PosixFilePermission.OWNER_EXECUTE
                permissions += PosixFilePermission.GROUP_EXECUTE
                permissions += PosixFilePermission.OTHERS_EXECUTE
                Files.setPosixFilePermissions(binary, permissions)
            }
        }

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

private val WHITESPACE = Regex("\\s+")

/** The first token of a sidecar body, as the source of the media tool publishes it. */
internal fun sidecarChecksum(body: String): String =
    body
        .trim()
        .split(WHITESPACE)
        .firstOrNull()
        .orEmpty()

/**
 * The checksum [assetName] carries in a multi-asset list: every line of the source is
 * `<sha256>  <asset>`; null when the body names none.
 */
internal fun checksumOf(sums: String, assetName: String): String? =
    sums
        .lineSequence()
        .map { line -> line.trim().split(WHITESPACE, limit = 2) }
        .firstOrNull { parts -> parts.size == 2 && parts[1] == assetName }
        ?.get(0)
        ?.takeIf(String::isNotEmpty)
