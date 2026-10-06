package io.averkhogliad.tubeloader.core.contract

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.Quality
import java.io.IOException
import java.nio.file.Path

/**
 * Responses recorded from a real source, keyed by URL.
 *
 * A URL listed as unreachable reproduces a transport failure. A URL that was never recorded throws
 * [IllegalArgumentException], so a gap in the recording fails the run instead of passing as a
 * business outcome of the adapter under test.
 */
class RecordedResponses(
    private val bodies: Map<String, ByteArray> = emptyMap(),
    private val unreachable: Set<String> = emptySet(),
) {
    fun bodyFor(url: String): ByteArray = when {
        bodies.containsKey(url) -> bodies.getValue(url)
        url in unreachable -> throw IOException("no response from $url")
        else -> throw IllegalArgumentException("no recorded response for $url")
    }

    companion object {
        val None = RecordedResponses()

        /**
         * Reads a recording from [root] on the classpath. Every response of the recording is a file
         * next to the manifest, so updating a fixture means editing a file, not editing test code.
         */
        fun fromResources(root: String): RecordedResponses {
            val manifest = readResource("$root/responses.txt").decodeToString()
            val bodies = mutableMapOf<String, ByteArray>()
            val unreachable = mutableSetOf<String>()
            manifest.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val url = line.substringBefore('=').trim()
                    val target = line.substringAfter('=').trim()
                    if (target == UNREACHABLE) {
                        unreachable += url
                    } else {
                        bodies[url] = readResource("$root/$target")
                    }
                }
            return RecordedResponses(bodies, unreachable)
        }

        private fun readResource(path: String): ByteArray = RecordedResponses::class.java.classLoader
            ?.getResourceAsStream(path)
            ?.use { it.readBytes() }
            ?: throw IllegalArgumentException("missing golden fixture: $path")

        private const val UNREACHABLE = "!unreachable"
    }
}

data class FindCase(
    val name: String,
    val input: String,
    val expected: FindResult,
)

data class MetaCase(
    val name: String,
    val id: String,
    val expected: LoadMetaResult,
)

class DownloadCase(
    val name: String,
    val id: String,
    val quality: Quality,
    val expected: DownloadResult,
    val expectedContent: ByteArray? = null,
    val expectNoFile: Boolean = expected is DownloadResult.Failed,
)

/**
 * Golden input of one adapter's contract run: the recording plus the reference outcomes the adapter
 * must reproduce.
 */
data class SourceAdapterFixtures(
    val responses: RecordedResponses,
    val find: List<FindCase>,
    val meta: List<MetaCase>,
    val download: List<DownloadCase>,
)

/**
 * Convenience for adapters whose recording lives under `golden/<name>/` on the classpath.
 */
fun recordedResponsesOf(sourceName: String): RecordedResponses = RecordedResponses.fromResources("golden/$sourceName")

/**
 * Where the suite writes the files it asserts on. Kept out of the fixture model because the path is
 * per-run, not per-source.
 */
fun downloadTargetOf(dir: Path, adapterName: String, case: DownloadCase): Path =
    dir.resolve("$adapterName-${case.id}-${case.quality.id}.bin")
