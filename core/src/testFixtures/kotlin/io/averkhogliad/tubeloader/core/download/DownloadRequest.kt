package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.Quality
import java.nio.file.Path

data class DownloadRequest(
    val id: String,
    val quality: Quality,
    val targetPath: Path,
)
