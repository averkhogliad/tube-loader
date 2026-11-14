package io.averkhogliad.tubeloader.tools.smoke

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.joran.spi.ConsoleTarget
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal const val LOG_PATTERN = "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n"

/**
 * The backend of the run: logback over the console, configured in code, so the run carries its
 * configuration in the artifact instead of relying on a resource of the classpath.
 *
 * The console appender writes to stderr and the dashboard paints stdout: the diagnostics of the
 * tools stay off the live table instead of breaking its rows. The notes of the run itself the
 * dashboard still takes directly; an appender that routes the diagnostics to the dashboard would be
 * attached here, next to the console one.
 */
internal fun configureLogging(
    context: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext,
    appenders: List<Appender<ILoggingEvent>> = emptyList(),
): LoggerContext {
    context.reset()
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.level = Level.INFO
    root.addAppender(consoleAppender(context))
    appenders.forEach(root::addAppender)
    context.start()
    return context
}

private fun consoleAppender(context: LoggerContext): ConsoleAppender<ILoggingEvent> =
    ConsoleAppender<ILoggingEvent>().apply {
        this.context = context
        name = "console"
        setTarget(ConsoleTarget.SystemErr.getName())
        encoder = patternEncoder(context)
        start()
    }

private fun patternEncoder(context: LoggerContext): PatternLayoutEncoder =
    PatternLayoutEncoder().apply {
        this.context = context
        pattern = LOG_PATTERN
        start()
    }
