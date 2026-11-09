package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.ToolPaths
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The first video ever uploaded: public and stable enough to resolve by a live tool. */
private const val LIVE_MEDIA_ID = "jNQXAC9IVRw"

/**
 * Drives the adapter over the real binary. Nothing of the tool is kept in the repository: the adapter
 * fetches the managed build into the home directory on its first call (ADR-0012), so the spec needs
 * no binary on the machine and no probe of its own.
 */
@Tag("integration")
@Tags("integration")
class YoutubeSourceAdapterIT :
    FreeSpec({

        "findById" - {
            "resolves the metadata and the video qualities of a live video" {
                // given
                val adapter =
                    YoutubeSourceAdapter {
                        YtDlpConfig(paths = ToolPaths.of(HOME), resolveTimeout = 60.seconds)
                    }

                // when
                val actual = adapter.findById(LIVE_MEDIA_ID)

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
