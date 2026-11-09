package io.averkhogliad.tubeloader.tools.smoke

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val DEFAULT_TARGET_DIR = "c:\\tmp\\smoke"
internal const val DEFAULT_PARALLEL = 3
internal const val EXIT_OK = 0

/**
 * A target of the run: what to download and what the file is called.
 *
 * Both forms address the same media element — a url is the same media id, only read from a link —
 * so both carry a name of their own: the name is set by a person, not by a source.
 */
internal sealed interface SmokeTarget {

    val name: String

    data class ByUrl(val url: String, override val name: String) : SmokeTarget

    data class ById(
        val code: String,
        val mediaId: String,
        override val name: String,
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
        """Usage: smoke [-url <url> -name <name>]... [-id <code>:<mediaId> -name <name>]...
       [-out <dir>] [-parallel <n>] [-cancel-after <s>]
Options:
  -url <url>      Video url to download, of any source the tool knows. Pairs with the next -name.
                  Can be given more than once.
  -id <code>:<mediaId>  Source code and media id to download, e.g. youtube:<id>. Pairs with the next -name.
  -name <name>    Target file name without extension. Required after every -url and -id.
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
    var i = 0

    fun nextValue(flag: String): String {
        if (i + 1 >= rawArgs.size) error("$flag requires a value\n${CliUsage.USAGE}")
        return rawArgs[++i]
    }

    while (i < rawArgs.size) {
        when (val arg = rawArgs[i]) {
            "-url" -> {
                val url = takeUrl(nextValue("-url"))
                targets += SmokeTarget.ByUrl(url, nameAfter(rawArgs, i, "-url $url"))
                i += 2
            }

            "-id" -> {
                val id = takeId(nextValue("-id"))
                targets += SmokeTarget.ById(id.code, id.mediaId, nameAfter(rawArgs, i, "-id ${id.code}:${id.mediaId}"))
                i += 2
            }

            "-name" -> {
                error(
                    "-name must follow its target: -url <url> -name <name> or " +
                        "-id <code>:<mediaId> -name <name>\n${CliUsage.USAGE}",
                )
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
                error("unknown argument '$arg'\n${CliUsage.USAGE}")
            }
        }
        i++
    }
    return CliArgs(targets, targetDir, parallel, cancelAfter)
}

/**
 * The name of a target is not optional and is the next pair of the command, right after the
 * address: a fixed arity of the pair is what tells every name apart from the target it belongs to.
 */
private fun nameAfter(rawArgs: Array<String>, at: Int, target: String): String {
    if (at + 1 >= rawArgs.size || rawArgs[at + 1] != "-name") {
        error("missing -name after $target\n${CliUsage.USAGE}")
    }
    if (at + 2 >= rawArgs.size) {
        error("-name requires a value\n${CliUsage.USAGE}")
    }
    return rawArgs[at + 2]
}

/**
 * The code of the source and the media id of the target: the separator is the first colon, the code
 * is lowered, and the id stays as it is — the case of an id is meaningful.
 *
 * A bare id is an error of the command: the source is not guessed from an id, the ids of two
 * sources look alike.
 */
private fun takeId(raw: String): TargetId {
    val separator = raw.indexOf(':')
    val code = if (separator > 0) raw.substring(0, separator).lowercase() else null
    val mediaId = if (separator in 0 until raw.length - 1) raw.substring(separator + 1) else null
    if (code.isNullOrEmpty() || mediaId.isNullOrEmpty()) {
        error("expected <code>:<mediaId> after -id, got '$raw'\n${CliUsage.USAGE}")
    }
    return TargetId(code, mediaId)
}

/** A url is told from the rest by its scheme alone; the source is not guessed from the host. */
private fun takeUrl(raw: String): String {
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
        error("expected a url after -url, got '$raw'\n${CliUsage.USAGE}")
    }
    return raw
}

private fun takeParallel(raw: String): Int {
    val parsed = raw.toIntOrNull() ?: error("-parallel must be an integer, got '$raw'\n${CliUsage.USAGE}")
    require(parsed >= 1) { "-parallel must be at least 1, got $parsed\n${CliUsage.USAGE}" }
    return parsed
}

private fun takeCancelAfter(raw: String): Duration {
    val parsed = raw.toIntOrNull() ?: error("-cancel-after must be an integer, got '$raw'\n${CliUsage.USAGE}")
    require(parsed >= 1) { "-cancel-after must be at least 1 second, got $parsed\n${CliUsage.USAGE}" }
    return parsed.seconds
}
