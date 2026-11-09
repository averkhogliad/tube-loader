package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.config.ToolPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream

private val WHITESPACE = Regex("\\s+")

/**
 * The checksum [assetName] carries in the `SHA2-256SUMS` body of the source, or null when the body
 * names none: every line of the source is `<sha256>  <asset>`.
 */
internal fun checksumOf(sums: String, assetName: String): String? =
    sums
        .lineSequence()
        .map { line -> line.trim().split(WHITESPACE, limit = 2) }
        .firstOrNull { parts -> parts.size == 2 && parts[1] == assetName }
        ?.get(0)
        ?.takeIf(String::isNotEmpty)

/**
 * The binary of the delegate tool is not shipped with the application and is not taken from the
 * machine: the first call fetches the official build into the yt-dlp directory of the home
 * directory, later calls reuse the file already there. The tool is then started by the absolute path
 * of that file, so nothing depends on the `PATH` of the machine nor on the directory the process
 * happens to run from.
 *
 * The fetch is the only network of the adapter and is deliberately not [io.averkhogliad.tubeloader.core.port.HttpTool]:
 * one GET on a cold start, no progress, no retries, and a failure is an [IOException] of the call.
 * Concurrent calls share one fetch: the file appears whole or not at all.
 *
 * [fetch] is the seam of the download: a test hands in its own, so no test reaches the network.
 */
internal class YtDlpDistribution(
    private val paths: ToolPaths,
    private val fetch: (Path) -> Unit = { binary -> fetchManaged(binary) },
) {

    private val mutex = Mutex()

    /**
     * @param override a path the config gives instead of the managed one: used as is, nothing is
     *   downloaded and nothing is checked.
     * @return the binary to run, or the reason it could not be obtained.
     */
    suspend fun ensureBinary(override: Path?): Result<Path> =
        override?.let { binary -> Result.success(binary) } ?: managedBinary()

    private suspend fun managedBinary(): Result<Path> {
        val managed = paths.ytDlpBinary
        return mutex.withLock {
            // a parallel call may have fetched the file while this one waited for the lock
            if (Files.isExecutable(managed)) {
                Result.success(managed)
            } else {
                withContext(Dispatchers.IO) { runCatching { fetch(managed) } }.map { managed }
            }
        }
    }
}

/** Fetches the build of the latest release: what the distribution calls on a miss. */
private fun fetchManaged(binary: Path) = fetchManaged(binary, YtDlpSource.current())

/**
 * Fetches [source] into [binary] through a temporary file of the same directory: a call that fails
 * half-way leaves no broken binary behind, and a reader never sees a partial one.
 *
 * The asset of the source is reached through the redirects of its host: a client that does not
 * follow them sees the body of a 302 and names no checksum.
 */
internal fun fetchManaged(binary: Path, source: YtDlpSource) {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val checksum =
        checksumOf(client.bodyOf(source.checksums), source.assetName)
            ?: throw NoChecksumInSource(source.assetName, source.checksums)
    val directory = requireNotNull(binary.parent) { "the binary path has no directory: $binary" }
    directory.createDirectories()
    val partial = Files.createTempFile(directory, binary.fileName.toString(), PARTIAL_SUFFIX)
    try {
        client.download(source.binary, partial)
        verifyChecksum(partial, checksum)
        moveIntoPlace(partial, binary)
        markExecutable(binary)
    } finally {
        Files.deleteIfExists(partial)
    }
}

private fun HttpClient.download(uri: URI, target: Path) {
    val response = exchange(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofFile(target))
    if (response.statusCode() != HTTP_OK) {
        throw DownloadFailed(uri, null, response.statusCode())
    }
}

private fun HttpClient.bodyOf(uri: URI): String =
    exchange(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

private fun <T> HttpClient.exchange(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
    try {
        send(request, handler)
    } catch (failure: IOException) {
        throw DownloadFailed(request.uri(), failure)
    } catch (failure: InterruptedException) {
        // the call runs on a thread of its own; restoring the flag keeps the interruption visible
        Thread.currentThread().interrupt()
        throw DownloadFailed(request.uri(), failure)
    }

private fun verifyChecksum(file: Path, expected: String) {
    val actual = sha256(file)
    if (!actual.equals(expected, ignoreCase = true)) {
        throw ChecksumMismatch(expected, actual)
    }
}

private fun moveIntoPlace(partial: Path, binary: Path) {
    try {
        Files.move(partial, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial, binary, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * A file written from the network carries no execution bit; on POSIX the tool would not start and
 * the next call would fetch it again. Windows has no such bit and does not need the call.
 */
private fun markExecutable(binary: Path) {
    runCatching {
        val permissions = Files.getPosixFilePermissions(binary).toMutableSet()
        permissions += PosixFilePermission.OWNER_EXECUTE
        permissions += PosixFilePermission.GROUP_EXECUTE
        permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(binary, permissions)
    }
}

private fun sha256(file: Path): String {
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

private const val HTTP_OK = 200
private const val PARTIAL_SUFFIX = ".part"

/**
 * Where the official build of the tool lives. The release is the latest one: a stale extractor is
 * the failure this tool exists to avoid.
 */
internal class YtDlpSource(
    val binary: URI,
    val checksums: URI,
    val assetName: String,
) {

    companion object {

        private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
        private const val WINDOWS_ASSET = "yt-dlp.exe"
        private const val SUMS_ASSET = "SHA2-256SUMS"

        /**
         * The standalone build of the source is published per platform; M1 takes the Windows one and
         * reports the others unavailable, as the media tool does with its own distribution.
         */
        fun current(osName: String = System.getProperty("os.name")): YtDlpSource =
            when {
                osName.lowercase().contains("win") -> {
                    YtDlpSource(
                        binary = URI.create(RELEASE + WINDOWS_ASSET),
                        checksums = URI.create(RELEASE + SUMS_ASSET),
                        assetName = WINDOWS_ASSET,
                    )
                }

                else -> {
                    throw UnavailableOn(Os.of(osName))
                }
            }
    }
}

private enum class Os {
    LINUX,
    MACOS,
    OTHER,
    ;

    companion object {
        fun of(osName: String): Os =
            when {
                osName.lowercase().contains("linux") -> LINUX
                osName.lowercase().contains("mac") -> MACOS
                else -> OTHER
            }
    }
}

/** Categories of the distribution failures: the call always sees a single [IOException]. */
private sealed class DistributionFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

private class DownloadFailed(
    uri: URI,
    cause: Throwable?,
    status: Int = 0,
) : DistributionFailure(
        if (status == 0) "cannot download $uri: ${cause?.message}" else "cannot download $uri: server answered $status",
        cause,
    )

private class ChecksumMismatch(expected: String, actual: String) :
    DistributionFailure("the file checksum does not match: expected $expected, got $actual")

private class NoChecksumInSource(asset: String, source: URI) :
    DistributionFailure("$source names no checksum of $asset")

private class UnavailableOn(os: Os) : DistributionFailure("no yt-dlp build is configured for $os")
