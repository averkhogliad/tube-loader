package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val UNROUTED_MEDIA_ID = "22222222222222222222222222222222"

/**
 * The sentinel an unrouted url meets: the recording fails it with an [IllegalArgumentException] rather
 * than answering it, so a gap in the golden fixture cannot pass for an outcome of the adapter.
 */
class RutubeRecordingSentinelTest :
    FreeSpec({

        "loadMeta" - {
            "surfaces the sentinel of an unrouted url as a failure a transport hiccup cannot be mistaken for" {
                // given
                val http = rutubeRecordedHttp()

                // when
                val actual = http.open(expectedOptionsUrl(UNROUTED_MEDIA_ID))

                // then
                actual.isFailure shouldBe true
                val failure = actual.exceptionOrNull()
                failure.shouldBeInstanceOf<IllegalArgumentException>()
            }

            "stops on the sentinel at once where a transport hiccup is retried" {
                // given
                val sentinel = rutubeRecordedHttp()
                val hiccup = FakeHttpTool().always(TRANSPORT_FAILURE_STUB)

                // when
                val sentinelResult = adapter(sentinel).loadMeta(UNROUTED_MEDIA_ID)
                val hiccupResult = adapter(hiccup).loadMeta(MEDIA_ID)

                // then
                sentinelResult shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
                hiccupResult shouldBe LoadMetaResult.Failed(DownloadError.NetworkTransient)
                sentinel.opened.size shouldBe 1
                hiccup.opened.size shouldBe 5
            }
        }
    })
