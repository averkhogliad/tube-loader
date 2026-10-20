package io.averkhogliad.tubeloader.core.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Paths

class ToolPathsTest :
    FreeSpec({

        "of" - {

            "keeps the home directory it was given" {
                // given
                val home = Paths.get("home", "tubeloader")

                // when
                val paths = ToolPaths.of(home, osName = "Linux")

                // then
                paths.homeDir shouldBe home
            }

            "points ffmpeg at the managed directory under the home directory" {
                // given / when
                val paths = ToolPaths.of(Paths.get("home", "tubeloader"), osName = "Linux")

                // then
                paths.ffmpegBinary shouldBe Paths.get("home", "tubeloader", ToolPaths.FFMPEG_DIR, "ffmpeg")
            }

            "names the executable with the extension only on Windows" {
                // given / when / then
                ToolPaths.executableName("Windows 11") shouldBe "ffmpeg.exe"
                ToolPaths.executableName("Linux") shouldBe "ffmpeg"
                ToolPaths.executableName("Mac OS X") shouldBe "ffmpeg"
            }
        }
    })
