package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.config.YtDlpConfig
import java.nio.file.Path

private const val WATCH_URL = "https://www.youtube.com/watch?v="

/** No distribution policy places the binary yet, so a bare name is left for the `PATH` of the machine. */
private val DEFAULT_BINARY: Path = Path.of("yt-dlp")

internal fun binaryOf(settings: YtDlpConfig): String = (settings.binaryPath ?: DEFAULT_BINARY).toString()

internal fun watchUrlOf(id: String): String = WATCH_URL + id
