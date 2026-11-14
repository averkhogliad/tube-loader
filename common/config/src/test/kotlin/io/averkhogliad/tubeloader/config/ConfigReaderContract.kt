package io.averkhogliad.tubeloader.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.factory.TestFactory
import io.kotest.core.spec.style.freeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private const val KEY = "value"

/**
 * The contract every [ConfigReader] adapter has to pass: the same leaf answers the same typed value,
 * a missing key answers null, and a leaf that cannot be read fails with the key and the value in the
 * message. An adapter plugs in with a factory over the raw leaves of its source:
 *
 * ```kotlin
 * class MemoryConfigReaderTest : FreeSpec({
 *     include(configReaderContract { values -> MemoryConfigReader(values) })
 * })
 * ```
 */
@Suppress("LongMethod")
fun configReaderContract(create: (Map<String, Any>) -> ConfigReader): TestFactory =
    freeSpec {
        "wholeNumber" - {
            "answers null for a missing key" {
                // given
                val reader = create(emptyMap())

                // when
                val actual = reader.wholeNumber(KEY)

                // then
                actual shouldBe null
            }

            "reads a whole number of the source" {
                // given
                val reader = create(mapOf(KEY to 3))

                // when
                val actual = reader.wholeNumber(KEY)

                // then
                actual shouldBe 3
            }

            "reads a whole number written as text" {
                // given
                val reader = create(mapOf(KEY to " 42 "))

                // when
                val actual = reader.wholeNumber(KEY)

                // then
                actual shouldBe 42
            }

            "rejects a value that is not a whole number" {
                // given
                val reader = create(mapOf(KEY to "many"))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.wholeNumber(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a whole number, got many"
            }

            "rejects a long outside the int range instead of truncating it" {
                // given
                val raw = Int.MAX_VALUE.toLong() + 1
                val reader = create(mapOf(KEY to raw))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.wholeNumber(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a whole number in the Int range, got $raw"
            }

            "rejects a value of a type that is not a number" {
                // given
                val reader = create(mapOf(KEY to true))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.wholeNumber(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a whole number, got true"
            }
        }

        "durationMillis" - {
            "answers null for a missing key" {
                // given
                val reader = create(emptyMap())

                // when
                val actual = reader.durationMillis(KEY)

                // then
                actual shouldBe null
            }

            "reads the milliseconds of a duration" {
                // given
                val reader = create(mapOf(KEY to 1500))

                // when
                val actual = reader.durationMillis(KEY)

                // then
                actual shouldBe 1500.milliseconds
            }

            "rejects a duration that is not a whole number" {
                // given
                val reader = create(mapOf(KEY to "soon"))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.durationMillis(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a whole number, got soon"
            }
        }

        "factor" - {
            "answers null for a missing key" {
                // given
                val reader = create(emptyMap())

                // when
                val actual = reader.factor(KEY)

                // then
                actual shouldBe null
            }

            "reads a fractional factor" {
                // given
                val reader = create(mapOf(KEY to 0.25))

                // when
                val actual = reader.factor(KEY)

                // then
                actual shouldBe 0.25
            }

            "reads a factor written as text" {
                // given
                val reader = create(mapOf(KEY to "0.5"))

                // when
                val actual = reader.factor(KEY)

                // then
                actual shouldBe 0.5
            }

            "rejects a factor that is not a number" {
                // given
                val reader = create(mapOf(KEY to "abc"))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.factor(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a number, got abc"
            }

            "rejects a value of a type that is not a number" {
                // given
                val reader = create(mapOf(KEY to true))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.factor(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a number, got true"
            }
        }

        "path" - {
            "answers null for a missing key" {
                // given
                val reader = create(emptyMap())

                // when
                val actual = reader.path(KEY)

                // then
                actual shouldBe null
            }

            "reads a path of the source" {
                // given
                val reader = create(mapOf(KEY to "opt/yt-dlp"))

                // when
                val actual = reader.path(KEY)

                // then
                actual shouldBe Path.of("opt", "yt-dlp")
            }

            "rejects an empty path" {
                // given
                val reader = create(mapOf(KEY to "  "))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.path(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a path, got an empty value"
            }

            "rejects a text that is not a path" {
                // given
                val raw = "a\u0000b"
                val reader = create(mapOf(KEY to raw))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.path(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a path, got $raw"
            }

            "rejects a value of a type that is not text" {
                // given
                val reader = create(mapOf(KEY to 5))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.path(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a path, got 5"
            }
        }

        "numberList" - {
            "answers null for a missing key" {
                // given
                val reader = create(emptyMap())

                // when
                val actual = reader.numberList(KEY)

                // then
                actual shouldBe null
            }

            "reads a list of whole numbers" {
                // given
                val reader = create(mapOf(KEY to listOf(429L, 503L)))

                // when
                val actual = reader.numberList(KEY)

                // then
                actual shouldBe listOf(429, 503)
            }

            "rejects a value that is not a list" {
                // given
                val reader = create(mapOf(KEY to 503))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.numberList(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a list of whole numbers, got 503"
            }

            "rejects an element that is not a whole number" {
                // given
                val reader = create(mapOf(KEY to listOf("abc")))

                // when
                val thrown = shouldThrow<IllegalArgumentException> { reader.numberList(KEY) }

                // then
                thrown.message shouldBe "$KEY must be a whole number, got abc"
            }
        }

        "text" - {
            "answers null for a missing key" {
                // given
                val reader = create(emptyMap())

                // when
                val actual = reader.text(KEY)

                // then
                actual shouldBe null
            }

            "reads the text of a value" {
                // given
                val reader = create(mapOf(KEY to "hello"))

                // when
                val actual = reader.text(KEY)

                // then
                actual shouldBe "hello"
            }

            "projects a number as text" {
                // given
                val reader = create(mapOf(KEY to 3))

                // when
                val actual = reader.text(KEY)

                // then
                actual shouldBe "3"
            }
        }
    }
