package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.MediaResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.domain.MediaRef
import io.averkhogliad.tubeloader.core.download.mediaMetas
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.inputs
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * The use case is a thin delegation: the outcomes of the resolution live in the suite of the facade,
 * here the delegation itself is checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveMediaTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "sources" - {
            "exposes the sources of the facade" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)

                    // when
                    val actual = ResolveMedia(world.facade).sources

                    // then
                    actual shouldBe world.facade.availableSources
                }
            }
        }

        "byUrl" - {
            "returns the answer of the facade for the url" {
                runTest {
                    // given
                    val meta = Arb.mediaMetas().next()
                    val mediaId = meta.id
                    val requested = mutableListOf<String>()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onResolveId = { input ->
                        requested += input
                        ResolveIdResult.Found(mediaId)
                    }
                    world.adapters.single().onFindById = { LoadMetaResult.Found(meta) }
                    val input = inputs.next()

                    // when
                    val actual = ResolveMedia(world.facade).byUrl(input)

                    // then
                    actual shouldBe MediaResult.Found(MediaRef(world.facade.availableSources.single(), mediaId), meta)
                    requested shouldBe listOf(input)
                }
            }
        }

        "byId" - {
            "returns the answer of the facade for the code and the id" {
                runTest {
                    // given
                    val meta = Arb.mediaMetas().next()
                    val mediaId = mediaIds.next()
                    val requested = mutableListOf<String>()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onFindById = { id ->
                        requested += id
                        LoadMetaResult.Found(meta)
                    }
                    val source = world.facade.availableSources.single()

                    // when
                    val actual = ResolveMedia(world.facade).byId(source.code, mediaId)

                    // then
                    actual shouldBe MediaResult.Found(MediaRef(source, mediaId), meta)
                    requested shouldBe listOf(mediaId)
                }
            }
        }
    })
