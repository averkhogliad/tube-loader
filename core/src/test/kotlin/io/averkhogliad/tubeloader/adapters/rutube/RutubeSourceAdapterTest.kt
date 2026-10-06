package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

private fun adapter() = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool(), { HttpToolConfig() })

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

        "find" - {
            "returns the media id of a canonical video url" {
                // when
                val actual = adapter().find("https://rutube.ru/video/$MEDIA_ID/")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns the media id of an embed url" {
                // when
                val actual = adapter().find("https://rutube.ru/play/embed/$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "ignores query parameters and fragments" {
                // when
                val actual = adapter().find("https://rutube.ru/video/$MEDIA_ID/?p=abc#t=1")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns Unsupported for a url of another host" {
                // when
                val actual = adapter().find("https://example.com/video/$MEDIA_ID/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for an id that is not a 32 character hex string" {
                // when
                val actual = adapter().find("https://rutube.ru/video/${MEDIA_ID.dropLast(1)}/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for an uppercase id" {
                // when
                val actual = adapter().find("https://rutube.ru/video/${MEDIA_ID.uppercase()}/")

                // then
                actual shouldBe FindResult.Unsupported
            }
        }
    })
