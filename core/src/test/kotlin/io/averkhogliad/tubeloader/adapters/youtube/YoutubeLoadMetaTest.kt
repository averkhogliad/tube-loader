package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val MEDIA_ID = "dQw4w9WgXcQ"

private val EXPECTED_META =
    MediaMeta(
        id = MEDIA_ID,
        title = "Big Buck Bunny",
        author = "Blender Foundation",
        duration = 596470.milliseconds,
        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        qualities =
            listOf(
                Quality("1080p", TrackKind.Video, "1080p"),
                Quality("720p", TrackKind.Video, "720p"),
                Quality("360p", TrackKind.Video, "360p"),
            ),
    )

class YoutubeLoadMetaTest :
    FreeSpec({

        "loadMeta" - {
            "returns Found with the meta parsed from the recording" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(output = goldenText("meta.json")))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Found(EXPECTED_META)
            }

            "asks the tool to resolve the media without downloading it" {
                // given
                val launches = mutableListOf<YtDlpLaunch>()
                val adapter = youtubeAdapter(launches = launches, reply = ToolReply(output = goldenText("meta.json")))

                // when
                adapter.loadMeta(MEDIA_ID)

                // then
                launches.single().command shouldBe
                    listOf(
                        "yt-dlp",
                        "--no-config",
                        "-J",
                        "--skip-download",
                        "https://www.youtube.com/watch?v=$MEDIA_ID",
                    )
            }

            "ignores unknown fields and tolerates the optional ones being absent" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(output = goldenText("meta-sparse.json")))

                // when
                val actual = adapter.loadMeta("sparse-1")

                // then
                val found = actual.shouldBeInstanceOf<LoadMetaResult.Found>()
                found.meta.author shouldBe ""
                found.meta.duration shouldBe Duration.ZERO
                found.meta.thumbnailUrl shouldBe null
                found.meta.qualities shouldBe listOf(Quality("360p", TrackKind.Video, "360p"))
            }

            "returns Failed(NotFound) when the tool says the media is gone" {
                // given
                val adapter =
                    youtubeAdapter(reply = ToolReply(exitedWith = 1, error = goldenText("error-unavailable.txt")))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NotFound)
            }

            "returns Failed(NetworkTransient) when the tool fails to reach the source" {
                // given
                val adapter =
                    youtubeAdapter(reply = ToolReply(exitedWith = 1, error = goldenText("error-network.txt")))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
            }

            "returns Failed(NetworkTransient) when the resolve outlives its budget" {
                // given
                val settings = YOUTUBE_SETTINGS.copy(resolveTimeout = 50.milliseconds)
                val adapter = youtubeAdapter(settings = settings, reply = ToolReply(exits = false))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
            }

            "returns Failed(ExtractorBroken) when the payload is not json" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(output = goldenText("meta-broken.txt")))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }

            "returns Failed(ExtractorBroken) when the payload names no video quality" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(output = goldenText("meta-no-qualities.json")))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }

            "returns Failed(ExtractorBroken) when the payload is of another media" {
                // given
                val adapter = youtubeAdapter(reply = ToolReply(output = goldenText("meta-foreign.json")))

                // when
                val actual = adapter.loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }
        }
    })
