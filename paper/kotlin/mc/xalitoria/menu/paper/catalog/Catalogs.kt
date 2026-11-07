package mc.xalitoria.menu.paper.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.MenuContext
import org.bukkit.Bukkit

/** The catalogs XaliMenu comes with. Text may hold placeholders: PlaceholderAPI's, and `{name}` for the menu's arguments. */
object Catalogs {
    /** The most elements a catalog gives, so a placeholder that comes out huge cannot make a menu build a list of millions. */
    const val LIMIT = 10_000

    fun register(registry: TypeRegistry) {
        registry.catalog<Players>(mapOf("name" to "Notch", "uuid" to "069a79f4-44e9-4726-a5be-fca90e38aaf5"))
        registry.catalog<Worlds>(mapOf("name" to "world", "environment" to "normal"))
        registry.catalog<Values>(mapOf("value" to "red"))
        registry.catalog<Range>(mapOf("value" to "1"))
    }

    /** The players who are online, by name: `name` and `uuid`. */
    @Serializable @SerialName("players")
    data class Players(val includeSelf: Boolean = true) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> = Bukkit.getOnlinePlayers()
            .filter { includeSelf || it.uniqueId != ctx.player.uniqueId }
            .sortedBy { it.name.lowercase() }
            .map { mapOf("name" to it.name, "uuid" to it.uniqueId.toString()) }
    }

    /** The worlds of the server: `name` and `environment` (`normal`, `nether` or `the_end`). */
    @Serializable @SerialName("worlds")
    data object Worlds : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> = Bukkit.getWorlds()
            .map { mapOf("name" to it.name, "environment" to it.environment.name.lowercase()) }
    }

    /**
     * Fixed texts, each one element with the value `value`: the entries of [list], then the pieces of [text] (placeholders
     * are filled in first) cut at [separator], which is read as written and not as a pattern. Empty pieces are left out.
     */
    @Serializable @SerialName("values")
    data class Values(
        val list: List<String> = [],
        val text: String? = null,
        val separator: String = ",",
        val trim: Boolean = true,
    ) : Catalog {
        init {
            require(separator.isNotEmpty()) { "the separator of values can't be empty" }
        }

        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val pieces = list.map(ctx::parse) + (text?.let { ctx.parse(it).split(separator) } ?: emptyList())
            return pieces.map { if (trim) it.trim() else it }.filter { it.isNotEmpty() }.take(LIMIT).map { mapOf("value" to it) }
        }
    }

    /** The whole numbers from [from] to [to], both included and given as numbers or placeholders, [step] apart: `value`. */
    @Serializable @SerialName("range")
    data class Range(val from: String, val to: String, val step: Int = 1, val descending: Boolean = false) : Catalog {
        init {
            require(step > 0) { "the step of a range must be above 0" }
        }

        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val start = number(ctx, from) ?: return emptyList()
            val end = number(ctx, to) ?: return emptyList()
            if (end < start) return emptyList()
            val values = generateSequence(start) { (it + step).takeIf { next -> next <= end } }.take(LIMIT).toList()
            return (if (descending) values.asReversed() else values).map { mapOf("value" to it.toString()) }
        }

        private fun number(ctx: MenuContext, text: String): Long? =
            ctx.parse(text).trim().toBigDecimalOrNull()?.toLong().also { if (it == null) debug("'{0}' isn't a number in a range", text) }
    }
}
