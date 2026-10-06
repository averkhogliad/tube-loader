package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.averkhogliad.tubeloader.core.port.HttpBody
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.milliseconds

private const val MEDIA_ID = "b9852a3ffc38640bdf480f5c9d4d912f"

private val EXPECTED_META =
    MediaMeta(
        id = MEDIA_ID,
        title = "Почему хороший продукт больше не преимущество: конкуренция, копирование и экономика IT-бизнеса #93",
        author = "Организованное программирование",
        duration = 5589952.milliseconds,
        thumbnailUrl =
            "https://pic.rtbcdn.ru/video/2026-09-20/c8/dc/c8dc7f1b3bfb649f3a0f86c1e2da5460.jpg",
        qualities = listOf(Quality(QUALITY_1080, TrackKind.Video, QUALITY_1080)),
    )

class RutubeLoadMetaTest : FreeSpec({

    "loadMeta" - {
        "returns Found with the meta parsed from the recording" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Found(EXPECTED_META)
        }

        "asks playOptions once with the referer the source requires" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            adapter.loadMeta(MEDIA_ID)

            // then
            val call = http.opened.single()
            call.url shouldBe
                "https://rutube.ru/api/play/options/$MEDIA_ID/?no_404=true&referer=https%253A%252F%252Frutube.ru&pver=v2"
            call.headers["Referer"] shouldBe "https://rutube.ru"
        }

        "returns NotFound for the source status that names a missing video" {
            // given
            val http =
                FakeHttpTool()
                    .recording("rutube/playOptions-notfound.json", status = 244)
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.NotFound
        }

        "returns Failed(ExtractorBroken) for a body that is not json" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions-broken.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        }

        "returns Failed(ExtractorBroken) when the body carries no video_id" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions-foreign.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        }

        "returns Failed(ExtractorBroken) for a media that is not the requested one" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions-other-id.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        }

        "returns Failed(ExtractorBroken) when the title is missing" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions-no-title.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        }

        "returns Failed(ExtractorBroken) when the body carries no playlist url" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions-no-balancer.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.ExtractorBroken)
        }

        "returns Failed(NetworkTransient) for a server error" {
            // given
            val http = FakeHttpTool().respondingWith(HttpBody(ByteArrayInputStream(ByteArray(0)), status = 503))
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            actual shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
        }

        "tolerates a response without author and duration" {
            // given
            val http = FakeHttpTool().recording("rutube/playOptions-minimal.json")
            val adapter = RutubeSourceAdapter(http, FakeMediaTool())

            // when
            val actual = adapter.loadMeta(MEDIA_ID)

            // then
            val found = actual.shouldBeInstanceOf<LoadMetaResult.Found>()
            found.meta.author shouldBe ""
            found.meta.duration shouldBe 0.milliseconds
            found.meta.thumbnailUrl shouldBe null
        }
    }
})
