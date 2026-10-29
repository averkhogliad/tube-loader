package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.TaskId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val DEFAULT_TARGET_DIR = "c:\\tmp\\rutube"
internal const val DEFAULT_PARALLEL = 3
internal const val EXIT_OK = 0

private const val HEX_RADIX = 16

internal data class CliArgs(
    val pairs: List<Pair<String, String>>,
    val targetDir: String,
    val parallel: Int,
    val cancelAfter: Duration?,
    val cancelTaskId: TaskId?,
)

internal object CliUsage {
    const val USAGE =
        """Usage: RutubeSmoke [-id <id> -name <name>]... [-out <dir>] [-parallel <n>] [-cancel-after <s>] [-cancel <taskId>]
Options:
  -id <id>        Rutube video id (32-char hex). Pairs with the next -name.
  -name <name>    Target file name without extension.
  -out <dir>      Target directory (default: c:\tmp\rutube).
  -parallel <n>   Max parallel downloads (default: 3).
  -cancel-after <s>  Cancel every running download <s> seconds after the first one starts.
  -cancel <taskId>   Cancel the download with the given task id (hex). The first download takes it.
"""
}

internal fun parseCli(rawArgs: Array<String>): CliArgs {
    val pairs = mutableListOf<Pair<String, String>>()
    var targetDir = DEFAULT_TARGET_DIR
    var parallel = DEFAULT_PARALLEL
    var cancelAfter: Duration? = null
    var cancelTaskId: TaskId? = null
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
                pendingId = takeName(pairs, pendingId, nextValue("-name"))
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

            "-cancel" -> {
                cancelTaskId = takeTaskId(nextValue("-cancel"))
            }

            "-h", "-help", "--help" -> {
                println(CliUsage.USAGE)
                kotlin.system.exitProcess(EXIT_OK)
            }

            else -> {
                error("Unknown argument: $arg\n${CliUsage.USAGE}")
            }
        }
        i++
    }
    if (pendingId != null) error("missing -name after -id $pendingId\n${CliUsage.USAGE}")
    return CliArgs(pairs, targetDir, parallel, cancelAfter, cancelTaskId)
}

private fun takeId(id: String, pendingId: String?): String {
    if (pendingId != null) error("missing -name after -id $pendingId\n${CliUsage.USAGE}")
    return id
}

private fun takeName(pairs: MutableList<Pair<String, String>>, pendingId: String?, name: String): String? {
    val id = pendingId ?: error("-name without preceding -id\n${CliUsage.USAGE}")
    pairs += id to name
    return null
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

private fun takeTaskId(raw: String): TaskId {
    val hex = raw.removePrefix("0x").removePrefix("0X")
    val parsed = hex.toIntOrNull(HEX_RADIX) ?: error("-cancel must be a hex task id, got '$raw'\n${CliUsage.USAGE}")
    return TaskId(parsed)
}
