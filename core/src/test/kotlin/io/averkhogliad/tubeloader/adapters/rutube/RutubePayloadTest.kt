package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"
private const val OPTIONS_URL =
    "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"

private val expectedMeta =
    MediaMeta(
        id = MEDIA_ID,
        title = "A video of the source",
        author = "Reference author",
        duration = 4200.milliseconds,
        thumbnailUrl = "https://pic.rtbcdn.ru/video/thumb.jpg",
        qualities = listOf(Quality("1080p", TrackKind.Video, "1080p")),
    )

private fun adapter(body: String) =
    RutubeSourceAdapter(
        FakeHttpTool().route(OPTIONS_URL, recordedStubWith(body, 200)),
        FakeMediaTool().copyStreams(),
    )

class RutubePayloadTest :
    FreeSpec({

        "loadMeta" - {
            "reads the fields it needs out of a payload that carries a dozen others" {
                // given
                val body =
                    """
                    {
                      "id": 469006435,
                      "video_id": "$MEDIA_ID",
                      "title": "A video of the source",
                      "thumbnail_url": "https://pic.rtbcdn.ru/video/thumb.jpg",
                      "duration": 4200,
                      "author": { "id": 35573563, "name": "Reference author", "logo": null },
                      "video_balancer": {
                        "default": "https://bl.rutube.ru/route/default.m3u8",
                        "m3u8": "https://bl.rutube.ru/route/master.m3u8"
                      },
                      "is_livestream": false,
                      "live_stream": null,
                      "tracks": [ { "kind": "audio" } ],
                      "origin": { "nested": { "deep": true } },
                      "duration_ms": 4200
                    }
                    """.trimIndent()

                // when
                val actual = adapter(body).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Found(expectedMeta)
            }

            "reports a broken extractor when the balancer carries neither playlist key" {
                // given
                val body =
                    """
                    {
                      "video_id": "$MEDIA_ID",
                      "title": "A video of the source",
                      "video_balancer": { "default": null, "m3u8": null }
                    }
                    """.trimIndent()

                // when
                val actual = adapter(body).loadMeta(MEDIA_ID)

                // then
                actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
            }
        }
    })
