package io.averkhogliad.tubeloader.infra.media

import io.averkhogliad.tubeloader.infra.binaries.BinaryPolicy
import io.averkhogliad.tubeloader.infra.binaries.ChecksumSource
import io.averkhogliad.tubeloader.infra.binaries.Os
import io.averkhogliad.tubeloader.infra.binaries.Unpack
import io.averkhogliad.tubeloader.infra.binaries.noBuildFor
import java.net.URI

private const val TOOL = "ffmpeg"
private const val VERSION = "8.1.2"
private const val CHECKSUM_SUFFIX = ".sha256"

private const val WINDOWS_PACKAGE =
    "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-$VERSION-essentials_build.zip"

/**
 * Where the official static build of the tool lives: the Windows archive published with the checksum
 * next to it, unpacked from the `bin/` entry of the archive.
 *
 * Windows is the only platform whose source publishes a checksum next to the archive in a form the
 * JVM can read: the Linux build is a `.tar.xz` (the JDK holds no xz) and the macOS one ships a GPG
 * signature instead. Fetching those is a ticket of its own, so on other platforms the policy reports
 * the tool unavailable rather than verifying nothing.
 */
fun ffmpegPolicy(osName: String = System.getProperty("os.name")): BinaryPolicy =
    when {
        osName.lowercase().contains("win") -> {
            val archive = URI.create(WINDOWS_PACKAGE)
            BinaryPolicy(
                tool = TOOL,
                source = archive,
                checksum = ChecksumSource.Sidecar(URI.create(archive.toString() + CHECKSUM_SUFFIX)),
                followRedirects = false,
                unpack = Unpack.ZipArchive,
            )
        }

        else -> {
            noBuildFor(TOOL, Os.of(osName))
        }
    }
