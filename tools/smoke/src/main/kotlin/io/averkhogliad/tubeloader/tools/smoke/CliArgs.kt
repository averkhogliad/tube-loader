package io.averkhogliad.tubeloader.tools.smoke

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val DEFAULT_TARGET_DIR = "c:\\tmp\\smoke"
internal const val DEFAULT_PARALLEL = 3
internal const val EXIT_OK = 0

/**
 * A target of the run: what to download and what the file is called.
 *
 * A url target leaves the source to the core, and the file is named after the media the source
 * answers with. An id target names the source by its code, and the name of the file is the one the
 * command gave: the name is set by a person, not by a source.
 */
internal sealed interface SmokeTarget {

    data class ByUrl(val url: String) : SmokeTarget

    data class ById(
        val code: String,
        val mediaId: String,
        val name: String,
    ) : SmokeTarget
}

internal data class CliArgs(
    val targets: List<SmokeTarget>,
    val targetDir: String,
    val parallel: Int,
    val cancelAfter: Duration?,
)

/** The code of the source and the media id of the target, as the command named them. */
private data class TargetId(val code: String, val mediaId: String)

internal object CliUsage {
    const val USAGE =
        """Usage: smoke [<url>]... [-id <code>:<mediaId> -name <name>]... [-out <dir>] [-parallel <n>] [-cancel-after <s>]
Options:
  <url>            Video url to download, of any source the tool knows. Can be given more than once.
  -id <code>:<mediaId>  Source code and media id to download, e.g. rutube:<id>. Pairs with the next -name.
  -name <name>    Target file name without extension.
  -out <dir>      Target directory (default: c:\tmp\smoke).
  -parallel <n>   Max parallel downloads (default: 3).
  -cancel-after <s>  Cancel every running download <s> seconds after the first one starts.
"""
}

internal fun parseCli(rawArgs: Array<String>): CliArgs {
    val targets = mutableListOf<SmokeTarget>()
    var targetDir = DEFAULT_TARGET_DIR
    var parallel = DEFAULT_PARALLEL
    var cancelAfter: Duration? = null
    var pendingId: TargetId? = null
    var i = 0

    fun nextValue(flag: String): String {
        if (i + 1 >= rawArgs.size) error("$flag requires a value\n${CliUsage.USAGE}")
        return rawArgs[++i]
    }
    while (i < rawArgs.size) {
        when (val arg = rawArgs[i]) {
            "-id" -> {
                pendingId = takeId(nextValue("-id"), pendingId)
            }

            "-name" -> {
                targets += takeName(pendingId, nextValue("-name"))
                pendingId = null
            }

            "-out" -> {
                targetDir = nextValue("-out")
            }

            "-parallel" -> {
                parallel = takeParallel(nextValue("-parallel"))
            }

            "-cancel-after" -> {
                cancelAfter = takeCancelAfter(nextValue("-cancel-after"))
            }

            "-h", "-help", "--help" -> {
                println(CliUsage.USAGE)
                kotlin.system.exitProcess(EXIT_OK)
            }

            else -> {
                targets += takeUrl(arg)
            }
        }
        i++
    }
    if (pendingId != null) error("missing -name after -id ${pendingId.code}:${pendingId.mediaId}\n${CliUsage.USAGE}")
    return CliArgs(targets, targetDir, parallel, cancelAfter)
}

/**
 * The code of the source and the media id of the target: the separator is the first colon, the code
 * is lowered, and the id stays as it is — the case of an id is meaningful.
 *
 * A bare id is an error of the command: the source is not guessed from an id, the ids of two
 * sources look alike.
 */
private fun takeId(raw: String, pendingId: TargetId?): TargetId {
    if (pendingId != null) {
        error("missing -name after -id ${pendingId.code}:${pendingId.mediaId}\n${CliUsage.USAGE}")
    }
    val separator = raw.indexOf(':')
    val code = if (separator > 0) raw.substring(0, separator).lowercase() else null
    val mediaId = if (separator in 0 until raw.length - 1) raw.substring(separator + 1) else null
    if (code.isNullOrEmpty() || mediaId.isNullOrEmpty()) {
        error("expected <code>:<mediaId> after -id, got '$raw'\n${CliUsage.USAGE}")
    }
    return TargetId(code, mediaId)
}

private fun takeName(pendingId: TargetId?, name: String): SmokeTarget {
    val id = pendingId ?: error("-name without preceding -id\n${CliUsage.USAGE}")
    return SmokeTarget.ById(id.code, id.mediaId, name)
}

private fun takeUrl(raw: String): SmokeTarget {
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
        error("expected a url, got '$raw'\n${CliUsage.USAGE}")
    }
    return SmokeTarget.ByUrl(raw)
}

private fun takeParallel(raw: String): Int {
    val parsed = raw.toIntOrNull() ?: error("-parallel must be an integer, got '$raw'\n${CliUsage.USAGE}")
    require(parsed >= 1) { "-parallel must be at least 1, got $parsed" }
    return parsed
}

private fun takeCancelAfter(raw: String): Duration {
    val parsed = raw.toIntOrNull() ?: error("-cancel-after must be an integer, got '$raw'\n${CliUsage.USAGE}")
    require(parsed >= 1) { "-cancel-after must be at least 1 second, got $parsed" }
    return parsed.seconds
}
