package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.SourceProgress
import io.averkhogliad.tubeloader.core.port.FakeHttpTool
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.readBytes

private val JOINED_SEGMENTS =
    listOf("rutube/segment-1.ts", "rutube/segment-2.ts", "rutube/segment-3.ts")
        .map(FakeHttpTool::resourceText)
        .joinToString("")
        .toByteArray()

class RutubeFinalizeTest :
    FreeSpec({

        "download" - {
            "hands the joined file over to remux and the result to the target path" {
                // given
                val media = FakeMediaTool().copyStreams()
                val dir = workDir("finalize")
                val target = dir.resolve("clip.mp4")

                // when
                val actual =
                    RutubeSourceAdapter(streaming(), media, { HttpToolConfig() })
                        .download(MEDIA_ID, VIDEO_1080, target) {}

                // then
                actual shouldBe DownloadResult.Success
                val call = media.remuxCalls.single()
                call.input shouldBe dir.resolve("clip.mp4.tmp")
                call.output shouldBe target
            }

            "leaves the remuxed file at the target and no tmp behind" {
                // given
                val dir = workDir("finalize")
                val target = dir.resolve("clip.mp4")

                // when
                adapter(streaming()).download(MEDIA_ID, VIDEO_1080, target) {}

                // then
                target.readBytes() shouldBe JOINED_SEGMENTS
                Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
            }

            "closes the progress with the size of the file it produced" {
                // given
                val target = clip("finalize")
                val progress = mutableListOf<SourceProgress>()

                // when
                adapter(streaming()).download(MEDIA_ID, VIDEO_1080, target) { progress += it }

                // then
                val written = target.readBytes().size.toLong()
                progress.last() shouldBe SourceProgress.Absolute(written, written)
            }

            "reports an indeterminate stage while the container is rewritten" {
                // given
                val progress = mutableListOf<SourceProgress>()

                // when
                adapter(streaming()).download(MEDIA_ID, VIDEO_1080, clip("finalize")) { progress += it }

                // then
                progress.takeLast(2).first() shouldBe SourceProgress.Indeterminate
            }

            "returns ExtractorBroken when the container rewrite fails" {
                // given
                val media = FakeMediaTool()
                media.onRemux = { _, _ -> Result.failure(IllegalStateException("ffmpeg is gone")) }
                val dir = workDir("finalize")

                // when
                val actual =
                    RutubeSourceAdapter(streaming(), media, { HttpToolConfig() }).download(
                        MEDIA_ID,
                        VIDEO_1080,
                        dir.resolve("clip.mp4"),
                    ) {}

                // then
                actual shouldBe DownloadResult.Failed(DownloadError.ExtractorBroken)
                Files.exists(dir.resolve("clip.mp4.tmp")) shouldBe false
            }
        }
    })
