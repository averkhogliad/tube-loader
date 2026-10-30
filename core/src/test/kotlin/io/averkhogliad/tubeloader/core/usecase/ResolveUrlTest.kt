package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.ResolveResult
import io.averkhogliad.tubeloader.core.adapter.FakeSourceAdapter
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.domain.SourceId
import io.averkhogliad.tubeloader.core.facade.FacadeSettings
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.inputs
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveUrlTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "sources" - {
            "exposes the sources of the facade" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)

                    // when
                    val actual = ResolveUrl(world.facade).sources

                    // then
                    actual shouldBe world.facade.availableSources
                }
            }

            "exposes one source per adapter in adapter order" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = ResolveUrl(world.facade).sources

                    // then
                    actual shouldBe world.facade.availableSources
                    actual.map { it.displayName } shouldBe listOf("alpha", "beta")
                }
            }
        }

        "byUrl" - {
            "returns Resolved with the id and source when one adapter claims the input" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }

                    // when
                    val actual = ResolveUrl(world.facade).byUrl(inputs.next())

                    // then
                    actual shouldBe
                        ResolveResult.Resolved(
                            MediaRef(world.facade.availableSources.single(), mediaId),
                        )
                }
            }

            "returns NotFound when the adapter recognizes the source but not the media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.NotFound }

                    // when
                    val actual = ResolveUrl(world.facade).byUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.NotFound
                }
            }

            "returns Unsupported when no adapter claims the input" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Unsupported }

                    // when
                    val actual = ResolveUrl(world.facade).byUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Unsupported
                }
            }

            "resolves against the claiming adapter among several" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    val mediaId = mediaIds.next()
                    second.onFind = { FindResult.Found(mediaId) }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = ResolveUrl(world.facade).byUrl(inputs.next())

                    // then
                    actual shouldBe
                        ResolveResult.Resolved(
                            MediaRef(world.facade.availableSources[1], mediaId),
                        )
                }
            }

            "throws on ambiguous match by two adapters" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    val firstId = mediaIds.next()
                    val secondId = mediaIds.next()
                    first.onFind = { FindResult.Found(firstId) }
                    second.onFind = { FindResult.Found(secondId) }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when + then
                    shouldThrow<IllegalStateException> {
                        ResolveUrl(world.facade).byUrl(inputs.next())
                    }
                }
            }
        }

        "byId" - {
            "returns Resolved when the named adapter recognizes the id" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    val mediaId = mediaIds.next()
                    second.onFind = { FindResult.Found(mediaId) }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))
                    val secondSource = world.facade.availableSources[1]

                    // when
                    val actual = ResolveUrl(world.facade).byId(secondSource.id, mediaId)

                    // then
                    actual shouldBe ResolveResult.Resolved(MediaRef(secondSource, mediaId))
                }
            }

            "returns NotFound when the named adapter has no such media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.NotFound }
                    val source = world.facade.availableSources.single()

                    // when
                    val actual = ResolveUrl(world.facade).byId(source.id, mediaIds.next())

                    // then
                    actual shouldBe ResolveResult.NotFound
                }
            }

            "throws on unknown source id" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)

                    // when + then
                    shouldThrow<IllegalStateException> {
                        ResolveUrl(world.facade).byId(SourceId(99), mediaIds.next())
                    }
                }
            }
        }
    })
