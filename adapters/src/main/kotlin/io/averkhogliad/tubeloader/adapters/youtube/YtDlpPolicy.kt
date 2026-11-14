package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.infra.binaries.BinaryPolicy
import io.averkhogliad.tubeloader.infra.binaries.ChecksumSource
import io.averkhogliad.tubeloader.infra.binaries.Os
import io.averkhogliad.tubeloader.infra.binaries.Unpack
import io.averkhogliad.tubeloader.infra.binaries.noBuildFor
import java.net.URI

private const val TOOL = "yt-dlp"
private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
private const val WINDOWS_ASSET = "yt-dlp.exe"
private const val SUMS_ASSET = "SHA2-256SUMS"

/**
 * Where the official build of the tool lives: the latest release, so a stale extractor never reaches
 * the source. The asset is the tool itself and its checksum is a line of the multi-asset sums of the
 * same release, reached through the redirects of the host.
 *
 * The standalone build of the source is published per platform; M1 takes the Windows one and reports
 * the others unavailable, as the media tool does with its own distribution.
 */
fun ytDlpPolicy(osName: String = System.getProperty("os.name")): BinaryPolicy =
    when {
        osName.lowercase().contains("win") -> {
            BinaryPolicy(
                tool = TOOL,
                source = URI.create(RELEASE + WINDOWS_ASSET),
                checksum = ChecksumSource.MultiAsset(URI.create(RELEASE + SUMS_ASSET), WINDOWS_ASSET),
                followRedirects = true,
                unpack = Unpack.ReadyFile,
            )
        }

        else -> {
            noBuildFor(TOOL, Os.of(osName))
        }
    }
