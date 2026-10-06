package io.averkhogliad.tubeloader.config

import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.TomlFile
import com.akuleshov7.ktoml.tree.nodes.TomlInlineTable
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValueArray
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValuePrimitive
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import com.akuleshov7.ktoml.tree.nodes.TomlTable
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlArray
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlValue
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class TomlConfig private constructor(private val leaves: Map<String, Any>) : Config {

    override val keys: Set<String> = leaves.keys

    override fun getOrNull(path: String): String? = leaves[path]?.toString()

    override fun getTableOrNull(path: String): Map<String, Any>? {
        val prefix = "$path."
        val matching = leaves.keys.filter { it.startsWith(prefix) }
        if (matching.isEmpty()) {
            return if (leaves.containsKey(path)) emptyMap() else null
        }
        return nestedView(path)
    }

    private fun nestedView(path: String): Map<String, Any> {
        val prefix = "$path."
        val direct =
            leaves
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
        val grouped = direct.entries.groupBy { it.key.substringBefore('.') }
        return grouped.mapValues { (_, entries) ->
            val single = entries.singleOrNull()?.key
            if (single != null && '.' !in single) return@mapValues entries.single().value
            val subPath = "$path.${entries.first().key.substringBefore('.')}"
            nestedView(subPath)
        }
    }

    companion object {
        fun fromString(toml: String): TomlConfig {
            val file: TomlFile = TomlParser(TomlInputConfig()).parseString(toml)
            val leaves = mutableMapOf<String, Any>()
            walk(file, prefix = "", out = leaves)
            return TomlConfig(leaves)
        }

        fun fromStream(stream: InputStream): TomlConfig =
            stream.reader(StandardCharsets.UTF_8).use { reader ->
                fromString(reader.readText())
            }

        fun fromFile(path: Path): TomlConfig = fromString(Files.readString(path))

        private fun walk(node: TomlNode, prefix: String, out: MutableMap<String, Any>) {
            for (child in node.children) {
                when (child) {
                    is TomlTable -> {
                        walk(child, "$prefix${child.name}.", out)
                    }

                    is TomlInlineTable -> {
                        walk(child, "$prefix${child.name}.", out)
                    }

                    is TomlKeyValuePrimitive -> {
                        out[prefix + normalizeKey(child.key)] = child.value.content
                    }

                    is TomlKeyValueArray -> {
                        val array = child.value as TomlArray
                        // the array hands out AST nodes; a reader of the config expects the values
                        out[prefix + normalizeKey(child.key)] =
                            array.parse(TomlInputConfig()).map { element ->
                                (element as? TomlValue)?.content ?: element
                            }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        private fun normalizeKey(key: com.akuleshov7.ktoml.tree.nodes.pairs.keys.TomlKey): String =
            key.toString().trim().let { raw ->
                val singleQuoted = raw.length >= 2 && raw.first() == '\'' && raw.last() == '\''
                val doubleQuoted = raw.length >= 2 && raw.first() == '"' && raw.last() == '"'
                if (singleQuoted || doubleQuoted) raw.substring(1, raw.length - 1) else raw
            }
    }
}
