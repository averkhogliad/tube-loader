package io.averkhogliad.tubeloader.config

import java.nio.file.Path

fun defaultSources(
    classpathResourceName: String = "config.toml",
    homeDir: Path = Path.of(System.getProperty("user.home"), ".tubeloader"),
    workingDir: Path = Path.of(System.getProperty("user.dir")),
    explicitFile: Path? = null,
): List<ConfigSource> =
    buildList {
        add(ClasspathConfigSource(classpathResourceName))
        add(FileConfigSource(homeDir.resolve("config.toml")))
        add(FileConfigSource(workingDir.resolve("config").resolve("config.toml")))
        add(FileConfigSource(workingDir.resolve("config.toml")))
        if (explicitFile != null) add(FileConfigSource(explicitFile, required = true))
    }
