package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The first video ever uploaded: public and stable enough to resolve by a live tool. */
private const val LIVE_MEDIA_ID = "jNQXAC9IVRw"

private const val BINARY = "yt-dlp"
private const val VERSION = "--version"

private const val PROBE_TIMEOUT_SECONDS = 30L

/**
 * Drives the adapter over the real binary. Nothing of the tool is kept in the repository, so a machine
 * without it skips the spec instead of failing it.
 */
@Tag("integration")
@Tags("integration")
class YoutubeSourceAdapterIT :
    FreeSpec({

        "loadMeta" - {
            "resolves the metadata and the video qualities of a live video".config(enabled = LIVE_BINARY_AVAILABLE) {
                // given
                val adapter =
                    YoutubeSourceAdapter {
                        YtDlpConfig(ffmpegLocation = HOME.resolve("ffmpeg"), resolveTimeout = 60.seconds)
                    }

                // when
                val actual = adapter.loadMeta(LIVE_MEDIA_ID)

                // then
                val found = actual as? LoadMetaResult.Found ?: error("expected a resolved metadata, got $actual")
                found.meta.run {
                    id shouldBe LIVE_MEDIA_ID
                    title.shouldNotBeBlank()
                    author.shouldNotBeBlank()
                    duration shouldBeGreaterThan Duration.ZERO
                    qualities.shouldNotBeEmpty()
                    qualities.map { it.kind }.toSet() shouldBe setOf(TrackKind.Video)
                }
            }
        }
    })

private val HOME: Path = Path.of(System.getProperty("user.home"), ".tubeloader")

/** Asked once: it drives the switch of the whole spec, so a silent answer is enough. */
private val LIVE_BINARY_AVAILABLE: Boolean by lazy {
    runCatching {
        val process = ProcessBuilder(BINARY, VERSION).redirectErrorStream(true).start()
        val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
    }.getOrDefault(false)
}
