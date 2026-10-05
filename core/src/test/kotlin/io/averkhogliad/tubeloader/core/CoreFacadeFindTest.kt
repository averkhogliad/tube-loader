package io.averkhogliad.tubeloader.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CoreFacadeFindTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "findByUrl" - {
            "returns Resolved with the id and source when one adapter claims the input" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaId) }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Resolved(
                        MediaRef(world.facade.availableSources.single(), mediaId),
                    )
                }
            }

            "returns Unsupported when no adapter claims the input" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Unsupported }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Unsupported
                }
            }

            "returns NotFound when the adapter recognizes the source but not the media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.NotFound }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.NotFound
                }
            }

            "routes to the matching adapter among several" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    val mediaId = mediaIds.next()
                    second.onFind = { FindResult.Found(mediaId) }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.Resolved(
                        MediaRef(world.facade.availableSources[1], mediaId),
                    )
                }
            }

            "throws on ambiguous match by two adapters" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Found("id-1") }
                    second.onFind = { FindResult.Found("id-2") }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when + then
                    shouldThrow<IllegalStateException> {
                        world.facade.findByUrl(inputs.next())
                    }
                }
            }

            "prefers NotFound over Unsupported when both occur" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(displayName = "alpha")
                    val second = FakeSourceAdapter(displayName = "beta")
                    first.onFind = { FindResult.Unsupported }
                    second.onFind = { FindResult.NotFound }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe ResolveResult.NotFound
                }
            }
        }

        "findById" - {
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
                    val actual = world.facade.findById(secondSource.id, mediaId)

                    // then
                    actual shouldBe ResolveResult.Resolved(MediaRef(secondSource, mediaId))
                }
            }

            "throws on unknown source id" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)

                    // when + then
                    shouldThrow<IllegalStateException> {
                        world.facade.findById(SourceId(99), mediaIds.next())
                    }
                }
            }
        }

        "loadMeta" - {
            "returns metadata from the resolved adapter" {
                runTest {
                    // given
                    val meta = Arb.mediaMetas().next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(meta.id) }
                    world.adapters.single().onLoadMeta = { LoadMetaResult.Found(meta) }
                    val ref = world.resolve(meta.id)

                    // when
                    val actual = world.facade.loadMeta(ref)

                    // then
                    actual shouldBe LoadMetaResult.Found(meta)
                }
            }

            "returns NotFound when the adapter has no such media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFind = { FindResult.Found(mediaIds.next()) }
                    world.adapters.single().onLoadMeta = { LoadMetaResult.NotFound }
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val actual = world.facade.loadMeta(ref)

                    // then
                    actual shouldBe LoadMetaResult.NotFound
                }
            }
        }
    })
