package io.averkhogliad.tubeloader.config

class ConfigProvider {

    private val sources = mutableListOf<ConfigSource>()

    fun addSource(vararg sources: ConfigSource): ConfigProvider {
        this.sources.addAll(sources)
        return this
    }

    fun load(): Config = MergedConfig(sources.mapNotNull { it.load() })
}
