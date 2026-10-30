package io.averkhogliad.tubeloader.core.media

import io.averkhogliad.tubeloader.core.config.ToolPaths
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class FfmpegDistributionTest :
    FreeSpec({

        lateinit var root: Path

        beforeSpec { root = Files.createTempDirectory("ffmpeg-distribution") }

        afterSpec { root.toFile().deleteRecursively() }

        "ensureManagedFfmpeg" - {
            "hands back the binary already unpacked in the home directory and fetches nothing" {
                // given
                val paths = ToolPaths.of(root)
                val managed =
                    paths.ffmpegBinary.apply {
                        parent.toFile().mkdirs()
                        writeText("stub")
                        toFile().setExecutable(true, false)
                    }

                // when
                val actual = ensureManagedFfmpeg(paths)

                // then
                actual.getOrNull() shouldBe managed
            }
        }
    })
