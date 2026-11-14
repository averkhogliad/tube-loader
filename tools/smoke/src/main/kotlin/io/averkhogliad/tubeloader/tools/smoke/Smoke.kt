package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.app.TubeloaderApp
import io.averkhogliad.tubeloader.config.Config
import io.averkhogliad.tubeloader.config.ConfigProvider
import io.averkhogliad.tubeloader.config.TomlConfigReader
import io.averkhogliad.tubeloader.config.defaultSources
import io.averkhogliad.tubeloader.core.config.ToolPaths
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

private val DEFAULT_TARGETS: List<SmokeTarget> = listOf()

private val SHUTDOWN_BUDGET = 30.seconds

/** The directory of the user data of the application, under the target directory of the run. */
private const val HOME_DIR = ".tubeloader"

fun main(args: Array<String>) {
    configureLogging()
    val cli = parseCli(args)
    val targets = cli.targets.ifEmpty { DEFAULT_TARGETS }
    runScript(targets, cli)
}

private fun runScript(targets: List<SmokeTarget>, cli: CliArgs) {
    val requestedDir = Paths.get(cli.targetDir ?: DEFAULT_TARGET_DIR)
    createDirectories(requestedDir)
    val homeDir = requestedDir.resolve(HOME_DIR)
    createDirectories(homeDir)
    val app = buildApp(homeDir, cli)
    val targetDir = app.config.defaultTargetDir ?: requestedDir
    val dashboard = ProgressDashboard()
    val session = SmokeSession(app, targetDir, dashboard)
    try {
        checkSourceCodes(session, targets)
        val rows = targetRows(targets)
        rows.forEach { row -> session.dashboard.register(row.key, formatPlanned(row)) }
        runBlocking {
            // the tools of the run are fetched at startup: a target waits for its source, not for the network
            val tools = session.app.prepareTools(session.dashboard::note)
            val observerJob = launch { session.observer.observe() }
            val cancelJob = launchCancelJobs(session, cli.cancelAfter)
            try {
                coroutineScope {
                    targets.forEachIndexed { index, target ->
                        launch { runOne(session, tools, target, rows[index]) }
                    }
                }
            } finally {
                observerJob.cancelAndJoin()
                cancelJob.cancelAndJoin()
            }
        }
    } finally {
        session.dashboard.close()
        runCatching { runBlocking { session.shutdown(SHUTDOWN_BUDGET) } }
        runCatching { app.close() }
    }
}

/**
 * The graph of the run: the composition root of the application, fed by the config of the tool.
 *
 * The settings come from the TOML config of the tool; the command line goes over it, and the
 * default of the application is the last backstop. A flag the command does not give stays absent
 * from the overrides, so the value of the config file stays in force.
 */
private fun buildApp(homeDir: Path, cli: CliArgs): TubeloaderApp =
    TubeloaderApp.build(TomlConfigReader(loadConfig(homeDir)), ToolPaths.of(homeDir), cli.overrides())

/**
 * The codes of the targets must be known before the first download starts.
 *
 * The list of the sources is known only after the graph is built, so the check lives here and not in
 * the parsing of the command. The registry of the sources is not duplicated: the codes come from
 * the composition root.
 */
internal fun requireKnownSourceCodes(targets: List<SmokeTarget>, knownCodes: List<String>) {
    val unknown =
        targets
            .filterIsInstance<SmokeTarget.ById>()
            .map { it.code }
            .distinct()
            .filter { it !in knownCodes }
    if (unknown.isEmpty()) return
    error(
        "unknown source code: ${unknown.joinToString(", ")}; " +
            "known codes: ${knownCodes.joinToString(", ")}\n${CliUsage.USAGE}",
    )
}

/**
 * The check of the codes of the targets, printed before the failure leaves the tool.
 *
 * The answer of the command is printed onto the dashboard, so it stays on the screen of the run
 * next to its rows; the stack trace of the failure leaves through stderr as any other failure.
 */
private fun checkSourceCodes(session: SmokeSession, targets: List<SmokeTarget>) {
    val codes = session.app.sources.map { source -> source.code }
    try {
        requireKnownSourceCodes(targets, codes)
    } catch (failure: IllegalStateException) {
        session.dashboard.note(failure.message ?: "the command names an unknown source")
        throw failure
    }
}

private fun loadConfig(homeDir: Path): Config {
    val provider = ConfigProvider()
    defaultSources(homeDir = homeDir).forEach { source -> provider.addSource(source) }
    return provider.load()
}
