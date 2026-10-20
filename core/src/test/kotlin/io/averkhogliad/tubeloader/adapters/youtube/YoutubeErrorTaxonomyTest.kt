package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val MEDIA_ID = "dQw4w9WgXcQ"

private val QUALITY = Quality("720p", TrackKind.Video, "720p")

/** One line of the mapping table of the spec: the recording of what the tool said and the class it names. */
private data class TaxonomyCase(val fixture: String, val expected: DownloadError)

private val TAXONOMY =
    listOf(
        TaxonomyCase("error-unavailable.txt", DownloadError.NotFound),
        TaxonomyCase("error-removed.txt", DownloadError.NotFound),
        TaxonomyCase("error-private.txt", DownloadError.NotFound),
        TaxonomyCase("error-forbidden.txt", DownloadError.UrlExpired),
        TaxonomyCase("error-gone.txt", DownloadError.UrlExpired),
        TaxonomyCase("error-network.txt", DownloadError.NetworkTransient),
        TaxonomyCase("error-antibot.txt", DownloadError.AntiBotBlocked),
        TaxonomyCase("error-format.txt", DownloadError.ExtractorBroken),
        TaxonomyCase("error-option.txt", DownloadError.ExtractorBroken),
        TaxonomyCase("error-crash.txt", DownloadError.ExtractorBroken),
    )

/** The adapter over a tool that ends with [exitCode] and says [fixture] on its error stream. */
private fun failingAdapter(
    fixture: String,
    exitCode: Int = 1,
    settings: YtDlpConfig = YOUTUBE_SETTINGS,
): YoutubeSourceAdapter =
    youtubeAdapter(
        settings = settings,
        reply = ToolReply(exitedWith = exitCode, error = goldenText(fixture)),
    )

class YoutubeErrorTaxonomyTest :
    FreeSpec({
        val roots = mutableListOf<Path>()

        afterSpec {
            roots.forEach { deleteWithRetry(it) }
        }

        "loadMeta" - {
            TAXONOMY.forEach { case ->
                "reads ${case.fixture} as ${case.expected}" {
                    // given
                    val adapter = failingAdapter(case.fixture)

                    // when
                    val actual = adapter.loadMeta(MEDIA_ID)

                    // then
                    actual shouldBe LoadMetaResult.Failed(case.expected)
                }
            }

            "does not let the exit code override what the tool said" {
                // given
                val adapter = failingAdapter("error-antibot.txt", exitCode = 2)

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.AntiBotBlocked)
            }

            "reads a failure the tool said nothing about as a broken extractor" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(exitedWith = 1))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }

            "reads a start that never reached the tool as a broken extractor" {
                // given
                val adapter = adapterOver(YOUTUBE_SETTINGS) { _ -> throw IOException("binary not found") }

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }

            "reads a start that never reached the tool as a broken extractor" {
                // given
                val adapter = adapterOver(YOUTUBE_SETTINGS) { _ -> throw IOException("binary not found") }

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }
        }

        "download" - {
            TAXONOMY.forEach { case ->
                "reads ${case.fixture} as ${case.expected}" {
                    // given
                    val world = TaxonomyWorld(roots)
                    val adapter = failingAdapter(case.fixture, settings = world.settings)

                    // when
                    val actual = adapter.download(MEDIA_ID, QUALITY, world.target) {}

                    // then
                    actual shouldBe DownloadResult.Failed(case.expected)
                }
            }

            "keeps the class the same as the resolve names for the same recording" {
                // given
                val world = TaxonomyWorld(roots)

                // when
                val transferred =
                    failingAdapter("error-antibot.txt", settings = world.settings)
                        .download(MEDIA_ID, QUALITY, world.target) {}
                val resolved = failingAdapter("error-antibot.txt").loadMeta(MEDIA_ID)

                // then
                transferred shouldBe DownloadResult.Failed(DownloadError.AntiBotBlocked)
                resolved shouldBe LoadMetaResult.Failed(DownloadError.AntiBotBlocked)
            }
        }
    })

/** A home directory of its own, so nothing of the tool lands in the directory the build runs from. */
private class TaxonomyWorld(roots: MutableList<Path>) {

    private val root: Path = Files.createTempDirectory("youtube-taxonomy").also { roots.add(it) }

    val settings = YtDlpConfig(paths = ToolPaths.of(root, "Linux"), binaryPath = Path.of("yt-dlp"))

    val target: Path = Files.createFile(root.resolve("video.part-1"))
}
