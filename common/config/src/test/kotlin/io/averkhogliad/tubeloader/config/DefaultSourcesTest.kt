package io.averkhogliad.tubeloader.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class DefaultSourcesTest : FreeSpec({

    "defaultSources" - {
        "returns the classpath source followed by three file sources" {
            // given
            val home = Path.of("home")
            val work = Path.of("work")

            // when
            val sources = defaultSources(homeDir = home, workingDir = work)

            // then
            sources shouldHaveSize 4
            sources.first().shouldBeInstanceOf<ClasspathConfigSource>()
            sources.drop(1).forEach { it.shouldBeInstanceOf<FileConfigSource>() }
        }

        "falls back to the process home and working directory by default" {
            // when
            val sources = defaultSources()

            // then
            sources shouldHaveSize 4
        }

        "leaves every absent source optional" {
            // given
            val sources = defaultSources(
                classpathResourceName = "no-such-resource.toml",
                homeDir = Path.of("no-such-home"),
                workingDir = Path.of("no-such-work"),
            )

            // when + then
            sources.forEach { it.load().shouldBeNull() }
        }

        "appends a required file source when an explicit file is given" {
            // given
            val explicit = Path.of("no-such-explicit-dir", "config.toml")

            // when
            val sources = defaultSources(
                classpathResourceName = "no-such-resource.toml",
                homeDir = Path.of("home"),
                workingDir = Path.of("work"),
                explicitFile = explicit,
            )

            // then
            sources shouldHaveSize 5
            shouldThrow<IllegalStateException> { sources.last().load() }
        }
    }
})
