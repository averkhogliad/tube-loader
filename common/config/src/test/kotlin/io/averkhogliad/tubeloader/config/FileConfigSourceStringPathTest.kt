package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class FileConfigSourceStringPathTest : FreeSpec({

    "load" - {
        "accepts a string path and reads the file it points at" {
            // given
            val file = Files.createTempFile("tubeloader-config-string", ".toml")
            Files.writeString(file, "download.max-parallel-downloads = 7")

            // when
            val config = FileConfigSource(file.toAbsolutePath().toString()).load()

            // then
            config?.getOrNull("download.max-parallel-downloads") shouldBe "7"
        }
    }
})
