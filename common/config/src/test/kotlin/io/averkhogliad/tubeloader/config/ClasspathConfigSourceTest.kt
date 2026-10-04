package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ClasspathConfigSourceTest : FreeSpec({

    "load" - {
        "parses the resource that is present on the classpath" {
            // given
            val source = ClasspathConfigSource("classpath-source-test.toml")

            // when
            val config = source.load()

            // then
            config?.getOrNull("classpath.value") shouldBe "7"
        }

        "returns null when no such resource is on the classpath" {
            // given
            val source = ClasspathConfigSource("no-such-resource.toml")

            // when
            val config = source.load()

            // then
            config.shouldBeNull()
        }
    }
})
