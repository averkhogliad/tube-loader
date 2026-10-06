package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"

class RutubeSourceAdapterTest :
    FreeSpec({

        "find" - {
            "returns the media id of a canonical video url" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/video/$MEDIA_ID/")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns the media id of an embed url" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/play/embed/$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "ignores query parameters and fragments" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/video/$MEDIA_ID/?p=abc#t=1")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns Unsupported for a url of another host" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://example.com/video/$MEDIA_ID/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for an id that is not a 32 character hex string" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/video/${MEDIA_ID.dropLast(1)}/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for an uppercase id" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/video/${MEDIA_ID.uppercase()}/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for a video url without an id" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/video/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for an unrelated rutube path" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("https://rutube.ru/video/person/35573563/")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for a string that is not a url" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when
                val actual = adapter.find("not a url at all")

                // then
                actual shouldBe FindResult.Unsupported
            }
        }

        "capability and displayName" - {
            "declares a native adapter named Rutube" {
                // given
                val adapter = RutubeSourceAdapter(FakeHttpTool(), FakeMediaTool())

                // when + then
                adapter.capability shouldBe DownloadCapability.Native
                adapter.displayName shouldBe "Rutube"
            }
        }
    })
