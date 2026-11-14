package io.averkhogliad.tubeloader.infra.binaries

import java.net.URI

/**
 * What the distribution needs to know about one tool: where its build comes from, how the checksum is
 * published, whether the source redirects, and what the downloaded asset is.
 *
 * The mechanism knows the shape of the policy, not the tool: everything tool-specific lives next to
 * the tool, as data.
 */
data class BinaryPolicy(
    val tool: String,
    val source: URI,
    val checksum: ChecksumSource,
    val followRedirects: Boolean,
    val unpack: Unpack,
)

/** How the source publishes the checksum of its asset. */
sealed interface ChecksumSource {

    /** A body next to the asset: the first token of the body is the checksum. */
    data class Sidecar(val source: URI) : ChecksumSource

    /** A list of `<checksum>  <asset>` lines: the line naming [asset] carries the checksum. */
    data class MultiAsset(val source: URI, val asset: String) : ChecksumSource
}

/** What the source publishes: an archive holding the tool or the tool itself. */
sealed interface Unpack {

    /** A zip archive; the tool is the entry named as the target binary or stored under `bin/`. */
    data object ZipArchive : Unpack

    /** The asset is the tool, ready to install as is. */
    data object ReadyFile : Unpack
}

/** The platforms the distribution knows by name: the policies report the ones they have no build for. */
enum class Os {
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
