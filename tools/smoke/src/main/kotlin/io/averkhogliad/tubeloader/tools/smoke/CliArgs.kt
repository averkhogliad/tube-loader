package io.averkhogliad.tubeloader.tools.smoke

internal const val DEFAULT_TARGET_DIR = "c:\\tmp\\rutube"
internal const val DEFAULT_PARALLEL = 3
internal const val EXIT_OK = 0

internal data class CliArgs(
    val pairs: List<Pair<String, String>>,
    val targetDir: String,
    val parallel: Int,
)

internal object CliUsage {
    const val USAGE =
        """Usage: RutubeSmoke [-id <id> -name <name>]... [-out <dir>] [-parallel <n>]
Options:
  -id <id>        Rutube video id (32-char hex). Pairs with the next -name.
  -name <name>    Target file name without extension.
  -out <dir>      Target directory (default: c:\tmp\rutube).
  -parallel <n>   Max parallel downloads (default: 3).
"""
}

internal fun parseCli(rawArgs: Array<String>): CliArgs {
    val pairs = mutableListOf<Pair<String, String>>()
    var targetDir = DEFAULT_TARGET_DIR
    var parallel = DEFAULT_PARALLEL
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
    return CliArgs(pairs, targetDir, parallel)
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
