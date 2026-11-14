package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.infra.binaries.BinaryPolicy
import io.averkhogliad.tubeloader.infra.binaries.ChecksumSource
import io.averkhogliad.tubeloader.infra.binaries.Os
import io.averkhogliad.tubeloader.infra.binaries.Unpack
import io.averkhogliad.tubeloader.infra.binaries.noBuildFor
import java.net.URI

private const val TOOL = "yt-dlp"
private const val RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
private const val SUMS_ASSET = "SHA2-256SUMS"
private const val WINDOWS_ASSET = "yt-dlp.exe"
private const val LINUX_ASSET = "yt-dlp_linux"
private const val LINUX_AARCH64_ASSET = "yt-dlp_linux_aarch64"
private const val MACOS_ASSET = "yt-dlp_macos"

/** The names the JVM gives the 64-bit architectures of the platforms the release publishes. */
private val X86_64_ARCHES = setOf("amd64", "x86_64")
private val AARCH64_ARCHES = setOf("aarch64", "arm64")

/**
 * Where the official build of the tool lives: the latest release, so a stale extractor never reaches
 * the source. The asset is the tool itself and its checksum is a line of the multi-asset sums of the
 * same release, reached through the redirects of the host.
 *
 * The source publishes a standalone build per platform: `yt-dlp.exe` for Windows, `yt-dlp_linux` for
 * x86-64 Linux, `yt-dlp_linux_aarch64` for aarch64 Linux and the universal `yt-dlp_macos` for macOS.
 * The layout of the managed binary ([io.averkhogliad.tubeloader.core.config.ToolPaths]) keeps the
 * platform-neutral name `yt-dlp` on POSIX, so a platform without a published build reports the tool
 * unavailable instead of installing a foreign one.
 */
fun ytDlpPolicy(
    osName: String = System.getProperty("os.name"),
    osArch: String = System.getProperty("os.arch"),
): BinaryPolicy {
    val asset = assetOf(osName, osArch)
    return BinaryPolicy(
        tool = TOOL,
        source = URI.create(RELEASE + asset),
        checksum = ChecksumSource.MultiAsset(URI.create(RELEASE + SUMS_ASSET), asset),
        followRedirects = true,
        unpack = Unpack.ReadyFile,
    )
}

private fun assetOf(osName: String, osArch: String): String =
    when {
        osName.lowercase().contains("win") -> WINDOWS_ASSET
        osName.lowercase().contains("mac") -> MACOS_ASSET
        osName.lowercase().contains("linux") -> linuxAssetOf(osArch)
        else -> noBuildFor(TOOL, Os.of(osName))
    }

private fun linuxAssetOf(osArch: String): String =
    when (osArch.lowercase()) {
        in X86_64_ARCHES -> LINUX_ASSET
        in AARCH64_ARCHES -> LINUX_AARCH64_ASSET
        else -> noBuildFor(TOOL, "${Os.LINUX.name} $osArch")
    }
