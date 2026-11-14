package io.averkhogliad.tubeloader.tools.smoke

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import ch.qos.logback.classic.Logger as LogbackLogger

private const val STALE_APPENDER = "stale"
private const val CONSOLE_APPENDER = "console"

private fun LoggerContext.root(): LogbackLogger = getLogger(LogbackLogger.ROOT_LOGGER_NAME)

private fun LoggerContext.console(): ConsoleAppender<ILoggingEvent> =
    root().getAppender(CONSOLE_APPENDER).shouldBeInstanceOf<ConsoleAppender<ILoggingEvent>>()

class LoggingTest :
    FreeSpec({

        "configureLogging" - {
            "replaces the configuration the context already had" {
                // given
                val context = LoggerContext()
                context.root().addAppender(ListAppender<ILoggingEvent>().apply { name = STALE_APPENDER })

                // when
                configureLogging(context)

                // then
                context.root().getAppender(STALE_APPENDER).shouldBeNull()
            }

            "attaches the console appender to the root logger" {
                // given
                val context = LoggerContext()

                // when
                configureLogging(context)

                // then
                context.console().name shouldBe CONSOLE_APPENDER
            }

            "starts the console appender, so it does not drop its events" {
                // given
                val context = LoggerContext()

                // when
                configureLogging(context)

                // then
                context.console().isStarted shouldBe true
            }

            "writes the console appender to stderr, away from the table of the dashboard" {
                // given
                val context = LoggerContext()

                // when
                configureLogging(context)

                // then
                context.console().target shouldBe "System.err"
            }

            "prints the level and the name of the logger in the pattern" {
                // given
                val context = LoggerContext()

                // when
                configureLogging(context)

                // then
                val encoder = context.console().encoder.shouldBeInstanceOf<PatternLayoutEncoder>()
                encoder.pattern shouldContain "%-5level"
                encoder.pattern shouldContain "%logger{36}"
            }

            "shows the diagnostics of the modules from info up" {
                // given
                val context = LoggerContext()

                // when
                configureLogging(context)

                // then
                context.root().level shouldBe Level.INFO
            }

            "attaches the appenders of the composition next to the console one" {
                // given
                val context = LoggerContext()
                val dashboard = ListAppender<ILoggingEvent>().apply { name = "dashboard" }

                // when
                configureLogging(context, listOf(dashboard))

                // then
                context.root().getAppender("dashboard") shouldBe dashboard
                context.console().name shouldBe CONSOLE_APPENDER
            }
        }
    })
