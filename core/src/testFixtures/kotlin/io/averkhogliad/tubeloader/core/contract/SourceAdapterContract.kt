package io.averkhogliad.tubeloader.core.contract

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.kotest.assertions.withClue
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.freeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes

/**
 * Contract suite every adapter must pass, driven by golden fixtures: the recording of the source
 * plus the reference `MediaMeta` / `Quality` / outcome the adapter has to reproduce.
 *
 * An adapter plugs in with one call — its dependencies are wired inside the factory lambda:
 *
 * ```kotlin
 * class RutubeAdapterTest : FreeSpec({
 *     include(sourceAdapterContract("rutube", rutubeFixtures()) { RutubeAdapter(http, media) })
 * })
 * ```
 *
 * The suite is offline by construction: the adapter answers from the recording, and a URL that was
 * not recorded fails the fixture rather than passing as an adapter outcome.
 */
fun sourceAdapterContract(
    adapterName: String,
    fixtures: SourceAdapterFixtures,
    create: () -> SourceAdapter,
): TestFactory =
    freeSpec {
        val tempDir = Files.createTempDirectory("contract-$adapterName")

        afterSpec {
            @OptIn(ExperimentalPathApi::class)
            tempDir.deleteRecursively()
        }

        "find" - {
            fixtures.find.forEach { case ->
                case.name {
                    // given
                    val adapter = create()

                    // when
                    val actual = adapter.find(case.input)

                    // then
                    actual shouldBe case.expected
                }
            }

            "covers both a matched and an unmatched outcome" {
                // given
                val adapter = create()
                val matched = fixtures.find.first { it.expected is FindResult.Found }
                val unmatched = fixtures.find.first { it.expected is FindResult.Unsupported }

                // when
                val matchedActual = adapter.find(matched.input)
                val unmatchedActual = adapter.find(unmatched.input)

                // then
                matchedActual shouldBe matched.expected
                unmatchedActual shouldBe unmatched.expected
            }

            "decides offline, without reading the recorded source" {
                // given: a url the recording knows nothing about — touching it would be a probe
                val adapter = create()
                val unrouted = "https://unrecorded.example/watch?v=nothing-recorded"

                // when
                val actual = adapter.find(unrouted)

                // then
                actual shouldBe FindResult.Unsupported
            }
        }

        "loadMeta" - {
            fixtures.meta.forEach { case ->
                case.name {
                    // given
                    val adapter = create()

                    // when
                    val actual = adapter.loadMeta(case.id)

                    // then
                    actual shouldBe case.expected
                }
            }

            "parses the recording into the reference meta and qualities" {
                // given
                val adapter = create()
                val case = fixtures.meta.first { it.expected is LoadMetaResult.Found }
                val reference = case.expected as LoadMetaResult.Found

                // when
                val actual = adapter.loadMeta(case.id).shouldBeInstanceOf<LoadMetaResult.Found>()

                // then
                actual.meta.id shouldBe reference.meta.id
                actual.meta.title shouldBe reference.meta.title
                actual.meta.author shouldBe reference.meta.author
                actual.meta.duration shouldBe reference.meta.duration
                actual.meta.thumbnailUrl shouldBe reference.meta.thumbnailUrl
                actual.meta.qualities shouldBe reference.meta.qualities
            }
        }

        "download" - {
            fixtures.download.forEach { case ->
                case.name {
                    // given
                    val adapter = create()
                    val target = downloadTargetOf(tempDir, adapterName, case).also { it.deleteIfExists() }
                    val progress = mutableListOf<SourceProgress>()

                    // when
                    val actual = adapter.download(case.id, case.quality, target) { progress += it }

                    // then
                    withClue("outcome") { actual shouldBe case.expected }
                    case.expectedContent?.let { reference ->
                        withClue("bytes written") { target.readBytes() shouldBe reference }
                    }
                    if (case.expectNoFile) {
                        withClue("file left behind") { Files.exists(target) shouldBe false }
                    }
                }
            }

            "reports progress within its own measure, reaching completion" {
                // given
                val adapter = create()
                val case = fixtures.download.first { it.expected is DownloadResult.Success }
                val target = downloadTargetOf(tempDir, adapterName, case).also { it.deleteIfExists() }
                val progress = mutableListOf<SourceProgress>()

                // when
                adapter.download(case.id, case.quality, target) { progress += it }

                // then
                progress.shouldNotBeEmpty()
                withClue("progress must stay within its own measure") {
                    progress.forEach { update -> update.fraction()?.let { (it in 0.0..1.0) shouldBe true } }
                }
                withClue("progress must reach completion") {
                    progress.last().fraction() shouldBe 1.0
                }
            }

            "writes the whole recorded stream, not a prefix of it" {
                // given
                val adapter = create()
                val case = fixtures.download.first { it.expected is DownloadResult.Success }
                val target = downloadTargetOf(tempDir, adapterName, case).also { it.deleteIfExists() }

                // when
                adapter.download(case.id, case.quality, target) {}

                // then
                Files.size(target) shouldBe case.expectedContent!!.size.toLong()
            }
        }
    }
