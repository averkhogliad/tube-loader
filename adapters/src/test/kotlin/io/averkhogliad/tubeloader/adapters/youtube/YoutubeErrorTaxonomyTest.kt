package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import io.averkhogliad.tubeloader.core.contract.deleteWithRetry
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.infra.binaries.BinaryFetch
import io.averkhogliad.tubeloader.infra.binaries.ManagedBinary
import io.averkhogliad.tubeloader.infra.binaries.ProcessOutcome
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException
import java.net.URI
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

/** The adapter over a distribution that never reaches the release. */
private fun unreachableDistributionAdapter(settings: YtDlpConfig): YoutubeSourceAdapter =
    YoutubeSourceAdapter(
        runner = YtDlpProcessRunner(FakeToolRunner { ToolReply() }, Path.of("work")),
        config = { settings },
        // M1 publishes the Windows build only: the policy is pinned so the host decides nothing here
        distribution = ManagedBinary(settings.paths.ytDlpBinary, { ytDlpPolicy("Windows 11") }, UnreachableRelease),
    )

private object UnreachableRelease : BinaryFetch {

    override suspend fun download(tool: String, source: URI, target: Path, followRedirects: Boolean): Unit =
        throw IOException("the release is unreachable")

    override suspend fun body(tool: String, source: URI, followRedirects: Boolean): String =
        error("the checksum of an unreachable release is never read")
}

class YoutubeErrorTaxonomyTest :
    FreeSpec({
        val roots = mutableListOf<Path>()

        afterSpec {
            roots.forEach { deleteWithRetry(it) }
        }

        "findById" - {
            TAXONOMY.forEach { case ->
                "reads ${case.fixture} as ${case.expected}" {
                    // given
                    val adapter = failingAdapter(case.fixture)

                    // when
                    val actual = adapter.findById(MEDIA_ID)

                    // then
                    val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                    failed.error shouldBe case.expected
                    failed.cause?.message.orEmpty() shouldContain goldenText(case.fixture).trim()
                }
            }

            "does not let the exit code override what the tool said" {
                // given
                val adapter = failingAdapter("error-antibot.txt", exitCode = 2)

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                failed.error shouldBe DownloadError.AntiBotBlocked
                failed.cause?.message.orEmpty() shouldContain goldenText("error-antibot.txt").trim()
            }

            "reads a failure the tool said nothing about as a broken extractor" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(exitedWith = 1))

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                failed.error shouldBe DownloadError.ExtractorBroken
                failed.cause.shouldBeInstanceOf<YtDlpFailed>()
                failed.cause?.message.orEmpty() shouldContain "the tool exited with code 1"
            }

            "reads a start that never reached the tool as a broken extractor" {
                // given
                val spawnFailure = IOException("binary not found")
                val adapter = adapterWithOutcome(YOUTUBE_SETTINGS, ProcessOutcome.SpawnFailed(spawnFailure))

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                failed.error shouldBe DownloadError.ExtractorBroken
                failed.cause shouldBeSameInstanceAs spawnFailure
            }

            "names the failure of the distribution as the cause" {
                // given
                val settings = YOUTUBE_SETTINGS.copy(binaryPath = null)
                val adapter = unreachableDistributionAdapter(settings)

                // when
                val actual = adapter.findById(MEDIA_ID)

                // then
                val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
                failed.error shouldBe DownloadError.ExtractorBroken
                failed.cause?.message.orEmpty() shouldContain "the release is unreachable"
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
                    val failed = actual.shouldBeInstanceOf<DownloadResult.Failed>()
                    failed.error shouldBe case.expected
                    failed.cause?.message.orEmpty() shouldContain goldenText(case.fixture).trim()
                }
            }

            "keeps the class the same as the resolve names for the same recording" {
                // given
                val world = TaxonomyWorld(roots)

                // when
                val transferred =
                    failingAdapter("error-antibot.txt", settings = world.settings)
                        .download(MEDIA_ID, QUALITY, world.target) {}
                val resolved = failingAdapter("error-antibot.txt").findById(MEDIA_ID)

                // then
                val transferFailure = transferred.shouldBeInstanceOf<DownloadResult.Failed>()
                val resolveFailure = resolved.shouldBeInstanceOf<LoadMetaResult.Failed>()
                transferFailure.error shouldBe DownloadError.AntiBotBlocked
                resolveFailure.error shouldBe DownloadError.AntiBotBlocked
                transferFailure.cause?.message.orEmpty() shouldContain "not a bot"
                resolveFailure.cause?.message.orEmpty() shouldContain "not a bot"
            }

            "names the failure of the distribution as the cause of a failed transfer" {
                // given
                val root = Files.createTempDirectory("youtube-distribution").also(roots::add)
                val settings = YOUTUBE_SETTINGS.copy(binaryPath = null)
                val adapter = unreachableDistributionAdapter(settings)

                // when
                val actual = adapter.download(MEDIA_ID, QUALITY, root.resolve("video.part-1")) {}

                // then
                val failed = actual.shouldBeInstanceOf<DownloadResult.Failed>()
                failed.error shouldBe DownloadError.ExtractorBroken
                failed.cause?.message.orEmpty() shouldContain "the release is unreachable"
            }
        }
    })

/** A home directory of its own, so nothing of the tool lands in the directory the build runs from. */
private class TaxonomyWorld(roots: MutableList<Path>) {

    private val root: Path = Files.createTempDirectory("youtube-taxonomy").also { roots.add(it) }

    val settings = YtDlpConfig(paths = ToolPaths.of(root, "Linux"), binaryPath = Path.of("yt-dlp"))

    val target: Path = Files.createFile(root.resolve("video.part-1"))
}
