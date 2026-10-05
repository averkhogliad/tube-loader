package io.averkhogliad.tubeloader.config

import java.nio.file.Files
import java.nio.file.Path

class FileConfigSource(private val path: Path, private val required: Boolean = false) : ConfigSource {

    constructor(path: String, required: Boolean = false) : this(Path.of(path), required)

    override fun load(): Config? {
        if (!Files.exists(path)) {
            if (required) error("Required config file not found: $path")
            return null
        }
        return TomlConfig.fromFile(path)
    }
}
