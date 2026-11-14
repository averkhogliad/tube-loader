package io.averkhogliad.tubeloader.infra.logging

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.slf4j.LoggerFactory

private const val EXPLICIT_NAME = "explicit.logger"

/** The delegate is declared by the base class: the name of the logger follows the runtime class. */
private open class BaseHolder {
    val logger by slf4j()
}

private class LoggerHolder : BaseHolder() {
    val named by slf4j(EXPLICIT_NAME)
    val typed by slf4j(LoggerHolder::class)
    val foreign by slf4j(String::class)
}

class Slf4jTest :
    FreeSpec({

        "slf4j" - {
            "names the logger after the class of the instance" {
                // given
                val holder = LoggerHolder()

                // when
                val actual = holder.logger.name

                // then
                actual shouldBe LoggerHolder::class.java.name
            }

            "names the logger after the runtime class, not the declaring one" {
                // given
                val holder: BaseHolder = LoggerHolder()

                // when
                val actual = holder.logger.name

                // then
                actual shouldBe LoggerHolder::class.java.name
            }

            "names the logger after the explicit name" {
                // given
                val holder = LoggerHolder()

                // when
                val actual = holder.named.name

                // then
                actual shouldBe EXPLICIT_NAME
            }

            "names the logger after the given class" {
                // given
                val holder = LoggerHolder()

                // when
                val actual = holder.typed.name

                // then
                actual shouldBe LoggerHolder::class.java.name
            }

            "names the logger after the given class, not after the class of the instance" {
                // given
                val holder = LoggerHolder()

                // when
                val actual = holder.foreign.name

                // then
                actual shouldBe String::class.java.name
            }

            "answers the logger the factory holds for the name" {
                // given
                val holder = LoggerHolder()

                // when
                val actual = holder.named

                // then
                actual shouldBeSameInstanceAs LoggerFactory.getLogger(EXPLICIT_NAME)
            }
        }
    })
