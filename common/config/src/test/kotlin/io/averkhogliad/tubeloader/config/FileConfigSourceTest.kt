package io.averkhogliad.tubeloader.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class FileConfigSourceTest : FreeSpec({

    val tempFiles = mutableListOf<Path>()

    afterSpec {
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    "load" - {
        "returns null when the file is absent" {
            // given
            val source = FileConfigSource(Path.of("no-such-dir", "config.toml"))

            // when
            val config = source.load()

            // then
            config.shouldBeNull()
        }

        "throws when the required file is absent" {
            // given
            val source = FileConfigSource(Path.of("no-such-dir", "config.toml"), required = true)

            // when + then
            shouldThrow<IllegalStateException> { source.load() }
        }

        "parses toml from an existing file" {
            // given
            val file = Files.createTempFile("tubeloader-config", ".toml")
            tempFiles.add(file)
            Files.writeString(file, "download.max-parallel-downloads = 2")

            // when
            val config = FileConfigSource(file).load()

            // then
            config?.getOrNull("download.max-parallel-downloads") shouldBe "2"
        }

        "accepts a string path and reads the file it points at" {
            // given
            val file = Files.createTempFile("tubeloader-config-string", ".toml")
            tempFiles.add(file)
            Files.writeString(file, "download.max-parallel-downloads = 7")

            // when
            val config = FileConfigSource(file.toAbsolutePath().toString()).load()

            // then
            config?.getOrNull("download.max-parallel-downloads") shouldBe "7"
        }
    }
})
