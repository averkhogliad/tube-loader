package io.averkhogliad.tubeloader.core.media

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * The tool is not shipped with the application: the first operation fetches the official static
 * build into the ffmpeg directory of the home directory, later ones reuse the file already there.
 *
 * The network client is deliberately not [io.averkhogliad.tubeloader.core.port.HttpTool]: one GET on a
 * cold start, no progress, no retries, and a failure is an [IOException] of the operation itself.
 */
internal class FfmpegDistribution(private val homeDir: Path) {

    /**
     * @param override a path the config gives instead of the managed one: used as is, nothing is
     *   downloaded and nothing is checked.
     * @return the binary to run, or the reason it could not be obtained.
     */
    fun ensureBinary(override: Path?): Result<Path> {
        val managed = binaryDir().resolve(ffmpegExecutableName())
        val reusable = override ?: managed.takeIf { binary -> Files.isExecutable(binary) }
        return reusable?.let { binary -> Result.success(binary) } ?: runCatching { fetch(managed) }
    }

    private fun fetch(binary: Path): Path {
        val source = FfmpegSource.current()
        val client = HttpClient.newHttpClient()
        val archive = Files.createTempFile("ffmpeg", source.archiveSuffix)

        try {
            val checksum = client.checksumOf(source.archive)
            download(client, source.archive, archive)
            verifyChecksum(archive, checksum)
            binaryDir().createDirectories()
            extract(archive, binary)
            markExecutable(binary)
        } finally {
            Files.deleteIfExists(archive)
        }
        return binary
    }

    private fun download(client: HttpClient, uri: URI, target: Path) {
        val response =
            exchange(client, HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() != HTTP_OK) {
            throw DownloadFailed(uri, null, response.statusCode())
        }
    }

    private fun HttpClient.checksumOf(archive: URI): String {
        val request = HttpRequest.newBuilder(URI.create(archive.toString() + CHECKSUM_SUFFIX)).GET().build()
        val body = exchange(this, request, HttpResponse.BodyHandlers.ofString()).body()
        return body
            .trim()
            .split(WHITESPACE)
            .firstOrNull()
            .orEmpty()
    }

    private fun <T> exchange(
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> =
        try {
            client.send(request, handler)
        } catch (failure: IOException) {
            throw DownloadFailed(request.uri(), failure)
        } catch (failure: InterruptedException) {
            // the operation runs on a thread of its own; restoring the flag keeps the interruption visible
            Thread.currentThread().interrupt()
            throw DownloadFailed(request.uri(), failure)
        }

    private fun verifyChecksum(archive: Path, expected: String) {
        val actual = sha256(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ChecksumMismatch(expected, actual)
        }
    }

    /** Writes the tool of [archive] to [target]; the archive of the source keeps it under `bin/`. */
    internal fun extract(archive: Path, target: Path) {
        val name = target.fileName.toString()
        ZipInputStream(archive.inputStream()).use { zip ->
            zip.advanceTo(name)
            target.outputStream().use { output -> zip.copyTo(output) }
            zip.closeEntry()
        }
    }

    /** Leaves the stream on the body of the entry holding the tool of the source. */
    private fun ZipInputStream.advanceTo(name: String) {
        generateSequence { nextEntry }
            .firstOrNull { entry -> entry.name.endsWith("/bin/$name") || entry.name == name }
            ?: throw NoBinaryInArchive(name)
    }

    /**
     * A file written from an archive carries no execution bit; on POSIX the tool would not start and
     * the next operation would fetch it again. Windows has no such bit and does not need the call.
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

    private fun binaryDir(): Path = homeDir.resolve(BINARY_DIR)

    private companion object {
        const val BINARY_DIR = "ffmpeg"
        const val CHECKSUM_SUFFIX = ".sha256"
        const val HTTP_OK = 200
        val WHITESPACE = Regex("\\s+")

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

/** Where the official static build of the tool lives. The version is the policy of the build. */
private class FfmpegSource(val archive: URI, val archiveSuffix: String) {

    companion object {

        private const val VERSION = "8.1.2"

        private const val WINDOWS_PACKAGE =
            "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-$VERSION-essentials_build.zip"

        /**
         * Windows is the only platform whose source publishes a checksum next to the archive in a form
         * the JVM can read: the Linux build is a `.tar.xz` (the JDK holds no xz) and the macOS one
         * ships a GPG signature instead. Fetching those is a ticket of its own, so on other platforms
         * the tool reports itself unavailable rather than verifying nothing.
         */
        fun current(osName: String = System.getProperty("os.name")): FfmpegSource =
            when {
                osName.lowercase().contains("win") -> FfmpegSource(URI.create(WINDOWS_PACKAGE), ".zip")
                else -> throw UnavailableOn(Os.of(osName))
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

/** Categories of the distribution failures: the operation always sees a single [IOException]. */
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
    DistributionFailure("the archive checksum does not match: expected $expected, got $actual")

private class NoBinaryInArchive(name: String) : DistributionFailure("the archive holds no $name")

private class UnavailableOn(os: Os) : DistributionFailure("no ffmpeg build is configured for $os")

/** The only platform difference the implementation makes: how the executable of the tool is named. */
internal fun ffmpegExecutableName(osName: String = System.getProperty("os.name")): String =
    if (osName.lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
