package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.DownloadCapability
import io.averkhogliad.tubeloader.core.adapter.FindResult
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

private const val MEDIA_ID = "dQw4w9WgXcQ"

private fun adapter() = youtubeAdapter()

class YoutubeSourceAdapterTest :
    FreeSpec({

        "capability" - {
            "delegates the transfer instead of streaming the source itself" {
                // when
                val actual = adapter().capability

                // then
                actual shouldBe DownloadCapability.Delegate
            }
        }

        "displayName" - {
            "names the source" {
                // when
                val actual = adapter().displayName

                // then
                actual shouldBe "YouTube"
            }
        }

        "find" - {
            "returns the media id of a watch url" {
                // when
                val actual = adapter().find("https://www.youtube.com/watch?v=$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns the media id of a short url" {
                // when
                val actual = adapter().find("https://youtu.be/$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns the media id of a shorts url" {
                // when
                val actual = adapter().find("https://youtube.com/shorts/$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns the media id of an embed url" {
                // when
                val actual = adapter().find("https://www.youtube.com/embed/$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "ignores query parameters and fragments around the id" {
                // when
                val actual = adapter().find("https://www.youtube.com/watch?feature=share&v=$MEDIA_ID&t=42s#top")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "decodes a url without a scheme" {
                // when
                val actual = adapter().find("m.youtube.com/watch?v=$MEDIA_ID")

                // then
                actual shouldBe FindResult.Found(MEDIA_ID)
            }

            "returns Unsupported for a url of another host" {
                // when
                val actual = adapter().find("https://vimeo.com/$MEDIA_ID")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns Unsupported for an input that is not a url" {
                // when
                val actual = adapter().find("not a url at all")

                // then
                actual shouldBe FindResult.Unsupported
            }

            "returns NotFound for a watch url without an id" {
                // when
                val actual = adapter().find("https://www.youtube.com/watch?feature=share")

                // then
                actual shouldBe FindResult.NotFound
            }

            "returns NotFound for a shorts url without an id" {
                // when
                val actual = adapter().find("https://youtube.com/shorts/")

                // then
                actual shouldBe FindResult.NotFound
            }

            "returns NotFound for a short url without an id" {
                // when
                val actual = adapter().find("https://youtu.be/")

                // then
                actual shouldBe FindResult.NotFound
            }
        }
    })
