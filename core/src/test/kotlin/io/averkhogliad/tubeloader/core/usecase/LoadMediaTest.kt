package io.averkhogliad.tubeloader.core.usecase

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.SourceId
import io.averkhogliad.tubeloader.core.download.downloadErrors
import io.averkhogliad.tubeloader.core.download.mediaMetas
import io.averkhogliad.tubeloader.core.facade.cleanUp
import io.averkhogliad.tubeloader.core.facade.facadeWorld
import io.averkhogliad.tubeloader.core.facade.mediaIds
import io.averkhogliad.tubeloader.core.facade.scratchDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LoadMediaTest :
    FreeSpec({

        val tempDir = scratchDir()

        afterSpec { cleanUp(tempDir) }

        "load" - {
            "returns Found with the metadata produced by the adapter" {
                runTest {
                    // given
                    val meta = Arb.mediaMetas().next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onLoadMeta = { LoadMetaResult.Found(meta) }
                    val ref = world.resolve(meta.id)

                    // when
                    val actual = LoadMedia(world.facade).load(ref)

                    // then
                    actual shouldBe LoadMetaResult.Found(meta)
                }
            }

            "returns NotFound when the adapter has no such media" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onLoadMeta = { LoadMetaResult.NotFound }
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val actual = LoadMedia(world.facade).load(ref)

                    // then
                    actual shouldBe LoadMetaResult.NotFound
                }
            }

            "returns Failed with the error produced by the adapter" {
                runTest {
                    // given
                    val error = Arb.downloadErrors().next()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onLoadMeta = { LoadMetaResult.Failed(error) }
                    val ref = world.resolve(mediaIds.next())

                    // when
                    val actual = LoadMedia(world.facade).load(ref)

                    // then
                    actual shouldBe LoadMetaResult.Failed(error)
                }
            }

            "asks the adapter of the source of the reference for the media id" {
                runTest {
                    // given
                    val mediaId = mediaIds.next()
                    val requested = mutableListOf<String>()
                    val world = facadeWorld(tempDir)
                    world.adapters.single().onLoadMeta = { id ->
                        requested += id
                        LoadMetaResult.NotFound
                    }
                    val ref = world.resolve(mediaId)

                    // when
                    LoadMedia(world.facade).load(ref)

                    // then
                    requested shouldBe listOf(mediaId)
                }
            }

            "propagates the failure of the facade for an unknown source" {
                runTest {
                    // given
                    val world = facadeWorld(tempDir)
                    val ref = world.resolve(mediaIds.next())
                    val unknown = ref.copy(source = ref.source.copy(id = SourceId(99)))

                    // when + then
                    shouldThrow<IllegalStateException> {
                        LoadMedia(world.facade).load(unknown)
                    }
                }
            }
        }
    })
