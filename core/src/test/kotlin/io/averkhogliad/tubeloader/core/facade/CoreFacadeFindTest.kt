package io.averkhogliad.tubeloader.core.facade

import io.averkhogliad.tubeloader.core.adapter.FakeSourceAdapter
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.download.downloadErrors
import io.averkhogliad.tubeloader.core.download.mediaMetas
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.test.runTest

class CoreFacadeFindTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "findByUrl" - {
            "returns Found with the ref and the meta when one adapter claims the input" {
                runTest {
                    // given
                    val meta = Arb.mediaMetas().next()
                    val mediaId = meta.id
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { ResolveIdResult.Found(mediaId) }
                    world.adapters.single().onFindById = { LoadMetaResult.Found(meta) }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe
                        MediaResult.Found(
                            MediaRef(world.facade.availableSources.single(), mediaId),
                            meta,
                        )
                }
            }

            "returns Unsupported when no adapter claims the input" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { ResolveIdResult.Unsupported }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe MediaResult.Unsupported
                }
            }

            "returns NotFound when the adapter recognizes the source but not the media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { ResolveIdResult.NotFound }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe MediaResult.NotFound
                }
            }

            "returns NotFound when the source has no such media" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { ResolveIdResult.Found(mediaId) }
                    world.adapters.single().onFindById = { LoadMetaResult.NotFound }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe MediaResult.NotFound
                }
            }

            "returns Failed with the error the source answered with" {
                runTest {
                    // given
                    val error = Arb.downloadErrors().next()
                    val cause = IllegalStateException("the source broke")
                    val mediaId = mediaIds.next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { ResolveIdResult.Found(mediaId) }
                    world.adapters.single().onFindById = { LoadMetaResult.Failed(error, cause) }

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe MediaResult.Failed(error, cause)
                }
            }

            "routes to the matching adapter among several" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(code = "alpha")
                    val second = FakeSourceAdapter(code = "beta")
                    first.onResolveId = { ResolveIdResult.Unsupported }
                    val mediaId = mediaIds.next()
                    second.onResolveId = { ResolveIdResult.Found(mediaId) }
                    val meta = Arb.mediaMetas().next()
                    second.onFindById = { LoadMetaResult.Found(meta) }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe
                        MediaResult.Found(
                            MediaRef(world.facade.availableSources[1], mediaId),
                            meta,
                        )
                }
            }

            "throws on ambiguous match by two adapters" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(code = "alpha")
                    val second = FakeSourceAdapter(code = "beta")
                    first.onResolveId = { ResolveIdResult.Found("id-1") }
                    second.onResolveId = { ResolveIdResult.Found("id-2") }
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
                    val first = FakeSourceAdapter(code = "alpha")
                    val second = FakeSourceAdapter(code = "beta")
                    first.onResolveId = { ResolveIdResult.Unsupported }
                    second.onResolveId = { ResolveIdResult.NotFound }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = world.facade.findByUrl(inputs.next())

                    // then
                    actual shouldBe MediaResult.NotFound
                }
            }
        }

        "findById" - {
            "returns Found with the ref and the meta of the named source" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(code = "alpha")
                    val second = FakeSourceAdapter(code = "beta")
                    val mediaId = mediaIds.next()
                    val meta = Arb.mediaMetas().next()
                    second.onFindById = { LoadMetaResult.Found(meta) }
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))
                    val secondSource = world.facade.availableSources[1]

                    // when
                    val actual = world.facade.findById(secondSource.code, mediaId)

                    // then
                    actual shouldBe MediaResult.Found(MediaRef(secondSource, mediaId), meta)
                }
            }

            "asks the adapter of the code for the media id, without reading it as a url" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val requested = mutableListOf<String>()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { ResolveIdResult.Unsupported }
                    world.adapters.single().onFindById = { id ->
                        requested += id
                        LoadMetaResult.NotFound
                    }
                    val source = world.facade.availableSources.single()

                    // when
                    world.facade.findById(source.code, mediaId)

                    // then
                    requested shouldBe listOf(mediaId)
                }
            }

            "returns NotFound when the source has no such media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFindById = { LoadMetaResult.NotFound }
                    val source = world.facade.availableSources.single()

                    // when
                    val actual = world.facade.findById(source.code, mediaIds.next())

                    // then
                    actual shouldBe MediaResult.NotFound
                }
            }

            "returns Failed with the error the source answered with" {
                runTest {
                    // given
                    val error = Arb.downloadErrors().next()
                    val cause = IllegalStateException("the source broke")
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFindById = { LoadMetaResult.Failed(error, cause) }
                    val source = world.facade.availableSources.single()

                    // when
                    val actual = world.facade.findById(source.code, mediaIds.next())

                    // then
                    actual shouldBe MediaResult.Failed(error, cause)
                }
            }

            "throws on unknown source code" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)

                    // when + then
                    shouldThrow<IllegalStateException> {
                        world.facade.findById("nowhere", mediaIds.next())
                    }
                }
            }
        }

        "availableSources" - {
            "holds the code and the name of every registered source" {
                runTest {
                    // given
                    val first = FakeSourceAdapter(code = "alpha")
                    val second = FakeSourceAdapter(code = "beta", displayName = "Beta")
                    val world = facadeWorld(tempDir, FacadeSettings(adapters = listOf(first, second)))

                    // when
                    val actual = world.facade.availableSources

                    // then
                    actual.map { it.code to it.displayName } shouldBe listOf("alpha" to "alpha", "beta" to "Beta")
                }
            }
        }
    })
