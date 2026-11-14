package io.averkhogliad.tubeloader.infra.binaries

import java.io.IOException
import java.net.URI

/** Categories of the distribution failures: the operation always sees a single [IOException]. */
internal sealed class DistributionFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class DownloadFailed(
    tool: String,
    uri: URI,
    cause: Throwable?,
    status: Int = 0,
) : DistributionFailure(
        if (status == 0) {
            "cannot download $tool from $uri: ${cause?.message}"
        } else {
            "cannot download $tool from $uri: server answered $status"
        },
        cause,
    )

internal class ChecksumMismatch(
    tool: String,
    expected: String,
    actual: String,
) : DistributionFailure("the $tool checksum does not match: expected $expected, got $actual")

internal class NoBinaryInArchive(tool: String, name: String) : DistributionFailure("the $tool archive holds no $name")

internal class NoChecksumInSource(
    tool: String,
    asset: String,
    source: URI,
) : DistributionFailure("$source names no checksum of $tool asset $asset")

internal class UnavailableOn(tool: String, platform: String) :
    DistributionFailure("no $tool build is configured for $platform")

/**
 * Reports that [tool] has no build for [os]. A policy calls this while it is being constructed: the
 * mechanism catches the failure and returns it as the outcome of the operation.
 */
fun noBuildFor(tool: String, os: Os): Nothing = throw UnavailableOn(tool, os.name)

/**
 * Reports that [tool] has no build for [platform] named by the policy: the same failure, with the
 * detail the policy knows (an architecture the release publishes no ready asset for, for example).
 */
fun noBuildFor(tool: String, platform: String): Nothing = throw UnavailableOn(tool, platform)
