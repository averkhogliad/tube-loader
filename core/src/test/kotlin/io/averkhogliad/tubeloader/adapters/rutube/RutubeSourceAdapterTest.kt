package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

private fun adapter() =
    RutubeSourceAdapter(
        FakeHttpTool(),
        FakeMediaTool(),
        { HttpToolConfig(perAttemptTimeout = 30.seconds) },
    )

class RutubeSourceAdapterTest :
    FreeSpec({

        "capability" - {
            "streams the source itself instead of delegating" {
                // when
                val actual = adapter().capability

                // then
                actual shouldBe DownloadCapability.Native
            }
        }

        "resolveId" - {
            "returns the media id of a canonical video url" {
                // when
                val actual = adapter().resolveId("https://rutube.ru/video/$MEDIA_ID/")

                // then
                actual shouldBe ResolveIdResult.Found(MEDIA_ID)
            }

            "returns the media id of an embed url" {
                // when
                val actual = adapter().resolveId("https://rutube.ru/play/embed/$MEDIA_ID")

                // then
                actual shouldBe ResolveIdResult.Found(MEDIA_ID)
            }

            "ignores query parameters and fragments" {
                // when
                val actual = adapter().resolveId("https://rutube.ru/video/$MEDIA_ID/?p=abc#t=1")

                // then
                actual shouldBe ResolveIdResult.Found(MEDIA_ID)
            }

            "returns Unsupported for a url of another host" {
                // when
                val actual = adapter().resolveId("https://example.com/video/$MEDIA_ID/")

                // then
                actual shouldBe ResolveIdResult.Unsupported
            }

            "returns NotFound for an id that is not a 32 character hex string" {
                // when
                val actual = adapter().resolveId("https://rutube.ru/video/${MEDIA_ID.dropLast(1)}/")

                // then
                actual shouldBe ResolveIdResult.NotFound
            }

            "returns NotFound for an uppercase id" {
                // when
                val actual = adapter().resolveId("https://rutube.ru/video/${MEDIA_ID.uppercase()}/")

                // then
                actual shouldBe ResolveIdResult.NotFound
            }

            "returns NotFound for a url of the source without a video" {
                // when
                val actual = adapter().resolveId("https://rutube.ru/")

                // then
                actual shouldBe ResolveIdResult.NotFound
            }
        }
    })
