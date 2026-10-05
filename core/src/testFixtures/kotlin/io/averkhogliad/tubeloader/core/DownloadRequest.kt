package io.averkhogliad.tubeloader.core

import java.nio.file.Path

data class DownloadRequest(val id: String, val quality: Quality, val targetPath: Path)
