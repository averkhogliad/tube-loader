package io.averkhogliad.tubeloader.core.contract

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.adapter.SourceAdapter
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.fraction
import io.kotest.assertions.withClue
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.freeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.milliseconds

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
 *
 * A failed reference outcome also carries a reference cause: the suite checks that the adapter names
 * the same reason in the message of its own cause, so the reason is part of the contract and not a
 * detail of a log.
 */
fun sourceAdapterContract(
    adapterName: String,
    fixtures: SourceAdapterFixtures,
    create: () -> SourceAdapter,
): TestFactory =
    freeSpec {
        val tempDir = Files.createTempDirectory("contract-$adapterName")

        afterSpec {
            deleteWithRetry(tempDir)
        }

        "resolveId" - {
            fixtures.resolveId.forEach { case ->
                case.name {
                    // given
                    val adapter = create()

                    // when
                    val actual = adapter.resolveId(case.input)

                    // then
                    actual shouldBe case.expected
                }
            }

            "covers both a matched and an unmatched outcome" {
                // given
                val adapter = create()
                val matched = fixtures.resolveId.first { it.expected is ResolveIdResult.Found }
                val unmatched = fixtures.resolveId.first { it.expected is ResolveIdResult.Unsupported }

                // when
                val matchedActual = adapter.resolveId(matched.input)
                val unmatchedActual = adapter.resolveId(unmatched.input)

                // then
                matchedActual shouldBe matched.expected
                unmatchedActual shouldBe unmatched.expected
            }

            "decides offline, without reading the recorded source" {
                // given: a url the recording knows nothing about — touching it would be a probe
                val adapter = create()
                val unrouted = "https://unrecorded.example/watch?v=nothing-recorded"

                // when
                val actual = adapter.resolveId(unrouted)

                // then
                actual shouldBe ResolveIdResult.Unsupported
            }
        }

        "findById" - {
            fixtures.meta.forEach { case ->
                case.name {
                    // given
                    val adapter = create()

                    // when
                    val actual = adapter.findById(case.id)

                    // then
                    assertMeta(case.expected, actual)
                }
            }

            "names a cause with every failed outcome" {
                // given
                val adapter = create()
                val failures =
                    fixtures.meta
                        .mapNotNull { case -> (case.expected as? LoadMetaResult.Failed)?.let { case to it } }
                        .filter { (_, expected) -> expected.cause != null }
                failures.shouldNotBeEmpty()

                failures.forEach { (case, expected) ->
                    // when
                    val actual = adapter.findById(case.id).shouldBeInstanceOf<LoadMetaResult.Failed>()

                    // then
                    withClue("cause of ${case.name}") {
                        actual.cause?.message.orEmpty() shouldContain expected.cause?.message.orEmpty()
                    }
                }
            }

            "parses the recording into the reference meta and qualities" {
                // given
                val adapter = create()
                val case = fixtures.meta.first { it.expected is LoadMetaResult.Found }
                val reference = case.expected as LoadMetaResult.Found

                // when
                val actual = adapter.findById(case.id).shouldBeInstanceOf<LoadMetaResult.Found>()

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
                    val progress = mutableListOf<Progress>()

                    // when
                    val actual = adapter.download(case.id, case.quality, target) { progress += it }

                    // then
                    withClue("outcome") { assertDownload(case.expected, actual) }
                    case.expectedContent?.let { reference ->
                        withClue("bytes written") { target.readBytes() shouldBe reference }
                    }
                    if (case.expectNoFile) {
                        withClue("file left behind") { Files.exists(target) shouldBe false }
                    }
                }
            }

            "names a cause with every failed transfer" {
                // given
                val adapter = create()
                val failures =
                    fixtures.download
                        .mapNotNull { case ->
                            (case.expected as? DownloadResult.Failed)?.let { case to it }
                        }.filter { (_, expected) -> expected.cause != null }
                failures.shouldNotBeEmpty()

                failures.forEach { (case, expected) ->
                    val target = downloadTargetOf(tempDir, adapterName, case).also { it.deleteIfExists() }

                    // when
                    val actual = adapter.download(case.id, case.quality, target) {}
                    val failed = actual.shouldBeInstanceOf<DownloadResult.Failed>()

                    // then
                    withClue("cause of ${case.name}") {
                        failed.cause?.message.orEmpty() shouldContain expected.cause?.message.orEmpty()
                    }
                }
            }

            "reports progress within its own measure, reaching completion" {
                // given
                val adapter = create()
                val case = fixtures.download.first { it.expected is DownloadResult.Success }
                val target = downloadTargetOf(tempDir, adapterName, case).also { it.deleteIfExists() }
                val progress = mutableListOf<Progress>()

                // when
                adapter.download(case.id, case.quality, target) { progress += it }

                // then
                progress.shouldNotBeEmpty()
                withClue("progress must reach completion") {
                    progress.last().fraction() shouldBe 1.0
                }
                // the share must be the work itself, not a decoration: a fraction that stands still
                // while the file grows, or runs backwards, is not progress
                val shares = progress.mapNotNull { it.fraction() }
                shares shouldBe shares.sorted()
                withClue("a completed download must report the whole file") {
                    progress.last() shouldBe Progress.Absolute(Files.size(target), Files.size(target))
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

/** The failed reference names the error class and the reason of the failure; anything else is an outcome. */
private fun assertMeta(expected: LoadMetaResult, actual: LoadMetaResult) {
    if (expected !is LoadMetaResult.Failed) {
        actual shouldBe expected
        return
    }
    val failed = actual.shouldBeInstanceOf<LoadMetaResult.Failed>()
    failed.error shouldBe expected.error
    assertCause(expected.cause, failed.cause)
}

/** The failed reference names the error class and the reason of the failure; anything else is an outcome. */
private fun assertDownload(expected: DownloadResult, actual: DownloadResult) {
    if (expected !is DownloadResult.Failed) {
        actual shouldBe expected
        return
    }
    val failed = actual.shouldBeInstanceOf<DownloadResult.Failed>()
    failed.error shouldBe expected.error
    assertCause(expected.cause, failed.cause)
}

private fun assertCause(expected: Throwable?, actual: Throwable?) {
    if (expected == null) {
        withClue("cause") { actual shouldBe null }
    } else {
        withClue("cause message") { actual?.message.orEmpty() shouldContain expected.message.orEmpty() }
    }
}

@OptIn(ExperimentalPathApi::class)
suspend fun deleteWithRetry(dir: Path) {
    val pauses = listOf(20, 40, 80, 160).map { it.milliseconds }
    var lastFailure: Exception? = null
    repeat(pauses.size + 1) { attempt ->
        try {
            dir.deleteRecursively()
            return
        } catch (failure: Exception) {
            lastFailure = failure
            pauses.getOrNull(attempt)?.let { delay(it) }
        }
    }
    throw lastFailure!!
}
