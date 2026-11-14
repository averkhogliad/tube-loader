package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.infra.binaries.ProcessLaunch
import io.averkhogliad.tubeloader.infra.binaries.ProcessOutcome
import io.averkhogliad.tubeloader.infra.binaries.ProcessRunner
import io.averkhogliad.tubeloader.infra.binaries.SystemProcessRunner
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration

private const val NO_CONFIG = "--no-config"
private const val ERROR_MARK = "ERROR:"
private const val PYTHON_UTF8 = "PYTHONUTF8"
private const val PYTHON_UTF8_ON = "1"

private val INHERITED_VARIABLES =
    setOf(
        "PATH",
        "PATHEXT",
        "COMSPEC",
        "SYSTEMROOT",
        "WINDIR",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "HOME",
        "LOCALAPPDATA",
        "APPDATA",
        "LANG",
        "LC_ALL",
    )

/** The tool takes its options only from the command line: a config left in the working directory is never read. */
internal fun withNoConfig(command: List<String>): List<String> {
    require(command.isNotEmpty()) { "the command names no binary" }
    return listOf(command.first(), NO_CONFIG) + command.drop(1).filterNot { option -> option == NO_CONFIG }
}

/**
 * The environment the tool runs in: nothing Python related is inherited, the text encoding is forced,
 * and the rest is what the process needs to start on its own platform.
 */
internal fun ytDlpEnvironment(inherited: Map<String, String>): Map<String, String> {
    val allowed = inherited.filterKeys { name -> INHERITED_VARIABLES.any { it.equals(name, ignoreCase = true) } }
    return allowed + (PYTHON_UTF8 to PYTHON_UTF8_ON)
}

/** What the tool wrote to its output stream, in order. */
internal data class YtDlpRun(val output: List<String>)

/**
 * Wraps the shared process mechanism for the tool: adds the options and the environment the tool
 * needs, and judges the outcome. The exit code alone decides nothing: the tool names its own
 * failures on the error stream, and an error reported there fails the run even when the code is
 * zero. The process tree, the budgets and the pipes belong to the mechanism.
 */
class YtDlpProcessRunner internal constructor(
    private val processRunner: ProcessRunner,
    private val workingDirectory: Path,
) {

    /** The composition root starts the tool as it is; the process seam of a test stays internal. */
    constructor(workingDirectory: Path) : this(SystemProcessRunner(), workingDirectory)

    internal suspend fun run(
        command: List<String>,
        timeout: Duration? = null,
        onOutputLine: (String) -> Unit = {},
    ): Result<YtDlpRun> {
        val launch =
            ProcessLaunch(
                command = withNoConfig(command),
                workingDirectory = workingDirectory,
                environment = ytDlpEnvironment(System.getenv()),
                timeout = timeout,
            )
        return when (val outcome = processRunner.run(launch, onOutputLine)) {
            is ProcessOutcome.Exited -> outcomeOf(outcome.exitCode, outcome.output, outcome.errorText)
            ProcessOutcome.TimedOut -> Result.failure(YtDlpTimedOut(timeout))
            is ProcessOutcome.SpawnFailed -> Result.failure(outcome.cause)
        }
    }

    private fun outcomeOf(exitCode: Int, output: List<String>, errorText: String): Result<YtDlpRun> {
        val error = errorText.trim()
        val reported = error.lineSequence().map(String::trim).firstOrNull { line -> line.startsWith(ERROR_MARK) }
        if (exitCode == 0 && reported == null) return Result.success(YtDlpRun(output))
        return Result.failure(YtDlpFailed(exitCode, error))
    }
}

/** The tool answered with a failure: the reason is what it said on its error stream. */
internal class YtDlpFailed(exitCode: Int, val toolMessage: String) :
    IOException(
        if (toolMessage.isEmpty()) {
            "the tool exited with code $exitCode"
        } else {
            "the tool exited with code $exitCode: $toolMessage"
        },
    )

/** The tool did not finish within the budget of its own call: the tree is already killed. */
internal class YtDlpTimedOut(timeout: Duration?) : IOException("the tool did not finish within $timeout")
