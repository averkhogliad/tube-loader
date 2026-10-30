package io.averkhogliad.tubeloader.tools.smoke

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val DEFAULT_TARGET_DIR = "c:\\tmp\\rutube"
internal const val DEFAULT_PARALLEL = 3
internal const val EXIT_OK = 0

/**
 * A target of the run: the url to download and the name of the file it lands in.
 *
 * [name] is null when the command named no file: the name is then the media id the source answers
 * with, so the file is named after the media and not after the url.
 */
internal data class SmokeTarget(val url: String, val name: String?)

internal data class CliArgs(
    val targets: List<SmokeTarget>,
    val targetDir: String,
    val parallel: Int,
    val cancelAfter: Duration?,
)

internal object CliUsage {
    const val USAGE =
        """Usage: smoke [<url>]... [-id <id> -name <name>]... [-out <dir>] [-parallel <n>] [-cancel-after <s>]
Options:
  <url>            Video url to download, of any source the tool knows. Can be given more than once.
  -id <id>        Rutube video id (32-char hex). Pairs with the next -name.
  -name <name>    Target file name without extension.
  -out <dir>      Target directory (default: c:\tmp\rutube).
  -parallel <n>   Max parallel downloads (default: 3).
  -cancel-after <s>  Cancel every running download <s> seconds after the first one starts.
"""
}

internal fun parseCli(rawArgs: Array<String>): CliArgs {
    val targets = mutableListOf<SmokeTarget>()
    var targetDir = DEFAULT_TARGET_DIR
    var parallel = DEFAULT_PARALLEL
    var cancelAfter: Duration? = null
    var pendingId: String? = null
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
    if (pendingId != null) error("missing -name after -id $pendingId\n${CliUsage.USAGE}")
    return CliArgs(targets, targetDir, parallel, cancelAfter)
}

private fun takeId(id: String, pendingId: String?): String {
    if (pendingId != null) error("missing -name after -id $pendingId\n${CliUsage.USAGE}")
    return id
}

private fun takeName(pendingId: String?, name: String): SmokeTarget {
    val id = pendingId ?: error("-name without preceding -id\n${CliUsage.USAGE}")
    return SmokeTarget(url = urlFor(id), name = name)
}

private fun takeUrl(raw: String): SmokeTarget {
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
        error("expected a url, got '$raw'\n${CliUsage.USAGE}")
    }
    return SmokeTarget(url = raw, name = null)
}

private fun urlFor(id: String): String = "https://rutube.ru/video/$id/"

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
