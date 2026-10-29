package mc.xalitoria.menu.paper.deluxemenus

/**
 * What converting one menu collects: the problems found, each with the place in the menu where it was found, and
 * the [materials] to look old item names up in.
 */
internal class Context(val materials: Materials) {
    val warnings = mutableListOf<String>()
    private var place = ""

    fun warn(message: String) {
        warnings += if (place.isEmpty()) message else "$place: $message"
    }

    /** Runs [block] with every warning in it prefixed by [label]. */
    fun <T> at(label: String, block: () -> T): T {
        val outer = place
        place = if (outer.isEmpty()) label else "$outer, $label"
        try {
            return block()
        } finally {
            place = outer
        }
    }

    /**
     * The item key for a DeluxeMenus `material`: `head-<player>` and the other prefixed sources become the `source:id`
     * form of the menu schema, and numeric ids and old names become today's key through [materials].
     */
    fun material(raw: String, data: Int?): String {
        val value = raw.trim()
        val lower = value.lowercase()

        val prefixed = PREFIXES.firstNotNullOfOrNull { (prefix, source) ->
            Regex("^${Regex.escape(prefix)}[-;:]", RegexOption.IGNORE_CASE).find(value)?.let {
                "$source:${value.substring(it.value.length)}"
            }
        }
        if (prefixed != null) return prefixed
        if (lower.startsWith("placeholder-")) return value.substring("placeholder-".length)
        if (lower == "main_hand" || lower == "off_hand" || lower.startsWith("armor_")) {
            warn("material '$value' is the item the viewer holds or wears, which isn't supported")
            return lower
        }

        val id = lower.substringBefore(':').toIntOrNull()
        if (id != null) {
            val resolved = materials.legacy(id, data ?: lower.substringAfter(':', "0").toIntOrNull() ?: 0)
            if (resolved == null) warn("material '$value' is an item id that couldn't be converted")
            return resolved ?: lower
        }

        val name = lower.removePrefix("minecraft:").replace(' ', '_')
        val legacy = when {
            data != null && data != 0 -> materials.legacy(name, data)
            !materials.exists(name) -> materials.legacy(name, 0)
            else -> null
        }
        if (legacy == null && !materials.exists(name)) warn("material '$value' isn't a known item")
        return legacy ?: name
    }

    private class Source(val prefix: String, val source: String)

    private companion object {
        /** Prefixes of DeluxeMenus materials and the source each names, longest first. */
        val PREFIXES = [
            Source("basehead", "texture"),
            Source("texture", "texture"),
            Source("head", "head"),
            Source("hdb", "hdb"),
            Source("itemsadder", "itemsadder"),
            Source("nexo", "nexo"),
        ]
    }
}
