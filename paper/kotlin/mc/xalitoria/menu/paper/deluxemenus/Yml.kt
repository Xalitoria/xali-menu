package mc.xalitoria.menu.paper.deluxemenus

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar

// DeluxeMenus reads its files loosely: a number may be quoted, a list may be a single value, and a key may hold
// nothing. These read a node the same way, giving null for whatever isn't there instead of failing.

/** The value under [key]; named apart from kaml's own generic `get`, which a member would shadow an extension of. */
internal fun YamlMap.option(key: String): YamlNode? =
    entries.entries.firstOrNull { it.key.content == key }?.value

internal val YamlMap.keys: List<String> get() = entries.keys.map { it.content }

internal fun YamlNode?.map(): YamlMap? = this as? YamlMap

internal fun YamlNode?.text(): String? = (this as? YamlScalar)?.content

/** A scalar as a one-element list, a list as its scalars. */
internal fun YamlNode?.texts(): List<String> = when (this) {
    is YamlScalar -> [content]
    is YamlList -> items.flatMap { it.texts() }
    else -> []
}

internal fun YamlNode?.int(): Int? = text()?.trim()?.toIntOrNull()

internal fun YamlNode?.bool(): Boolean? = text()?.trim()?.lowercase()?.toBooleanStrictOrNull()
