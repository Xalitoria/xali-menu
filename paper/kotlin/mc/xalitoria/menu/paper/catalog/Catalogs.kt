package mc.xalitoria.menu.paper.catalog

import net.xalitoria.menu.model.Within
import net.xalitoria.menu.model.WithinRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.MenuContext
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale

/** The catalogs XaliMenu comes with. Text may hold placeholders: PlaceholderAPI's, and `{name}` for the menu's arguments. */
object Catalogs {
    /** The most elements a catalog gives, so a placeholder that comes out huge cannot make a menu build a list of millions. */
    const val LIMIT = 10_000

    private val PLAIN = PlainTextComponentSerializer.plainText()

    fun register(registry: TypeRegistry) {
        registry.catalog<Players>("name" to "Steve", "uuid" to "8667ba71-b85a-4004-af54-457a9734eed7")
        registry.catalog<Worlds>("name" to "world", "environment" to "normal")
        registry.catalog<Values>("value" to "red")
        registry.catalog<Range>("value" to "1")
        registry.catalog<Menus>("name" to "shop", "title" to "Shop")
        registry.catalog<Inventory>("slot" to "0", "material" to "diamond_sword", "amount" to "1", "name" to "Excalibur")
        registry.catalog<Entities>(
            "type" to "villager", "name" to "Villager", "uuid" to "8667ba71-b85a-4004-af54-457a9734eed7",
            "distance" to "4.5", "x" to "10", "y" to "64", "z" to "-20", "world" to "world",
        )
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
     * The menus that are loaded, by name: `name` (what `/xmenu open` and the `open` action take) and `title` (as written in the file,
     * placeholders and all). The menu that lists them is left out unless [includeSelf]. A menu of menus: `open { menu = "{name}" }`.
     */
    @Serializable @SerialName("menus")
    data class Menus(val includeSelf: Boolean = false) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> = XaliMenu.loadedMenus()
            .filter { includeSelf || !it.name.equals(ctx.menu, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { mapOf("name" to it.name, "title" to it.menu.title) }
    }

    /**
     * The items in the viewer's inventory, by slot: `slot` (0 to 35, the hotbar first, then `armor` and `offhand` if [armor] is on),
     * `material` (`diamond_sword`), `amount` and `name` (the custom name as plain text, or nothing). An element is drawn like any
     * other, so `material = "{material}"` and `amount = "{amount}"` show the item; what else it holds (enchantments, lore) stays on the item.
     */
    @Serializable @SerialName("inventory")
    data class Inventory(val armor: Boolean = false, val empty: Boolean = false) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val inventory = ctx.player.inventory
            val named = (0 until 36).map { it.toString() to inventory.getItem(it) } +
                (if (armor) listOf("helmet" to inventory.helmet, "chestplate" to inventory.chestplate, "leggings" to inventory.leggings, "boots" to inventory.boots, "offhand" to inventory.itemInOffHand) else emptyList())
            return named.mapNotNull { [slot, item] ->
                if (item == null || item.type.isAir) {
                    if (empty) mapOf("slot" to slot, "material" to "air", "amount" to "0", "name" to "") else null
                } else {
                    val name = item.itemMeta?.takeIf { it.hasCustomName() }?.customName()?.let { PLAIN.serialize(it) } ?: ""
                    mapOf("slot" to slot, "material" to item.type.key.key, "amount" to item.amount.toString(), "name" to name)
                }
            }
        }
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
    data class Range(val from: String, val to: String, @Within(min = 1, strict = true) val step: Int = 1, val descending: Boolean = false) : Catalog {
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

    /**
     * Entities near the viewer within [radius] blocks (0.5 to 128, bounded so it stays safe on Folia's region threads):
     * `type` (entity key such as `villager`), `name` (custom name if set, otherwise the entity name), `uuid`,
     * `distance` (in blocks, rounded to one decimal), `x`, `y`, `z`, and `world`. Ordered closest first.
     */
    @Serializable @SerialName("entities")
    data class Entities(
        /** Search radius in blocks around the viewer (0.5 to 128). */
        @WithinRange(0.5, 128.0, strict = true)
        val radius: Double = 32.0,
        /** Entity type keys to include (for example `villager`, `zombie`); any entity when empty. */
        val types: List<String> = [],
        /** Only list entities that have a custom name. */
        val namedOnly: Boolean = false,
        /** Include other players in the results. */
        val includePlayers: Boolean = false,
    ) : Catalog {
        init {
            require(radius in 0.5..128.0) { "entities radius must be between 0.5 and 128" }
        }

        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val viewer = ctx.player
            val origin = viewer.location
            val maxDistSq = radius * radius
            val allowed = types.map { it.trim().lowercase().removePrefix("minecraft:") }.filter { it.isNotEmpty() }.toSet()
            return viewer.getNearbyEntities(radius, radius, radius)
                .asSequence()
                .filter { includePlayers || it !is Player }
                .filter { entity -> allowed.isEmpty() || entity.type.key.key in allowed }
                .filter { entity -> !namedOnly || entity.customName() != null }
                .mapNotNull { entity ->
                    val loc = entity.location
                    if (loc.world != origin.world) return@mapNotNull null
                    val distSq = loc.distanceSquared(origin)
                    if (distSq > maxDistSq) return@mapNotNull null
                    val dist = kotlin.math.sqrt(distSq)
                    val custom = entity.customName()?.let(PLAIN::serialize)
                    dist to mapOf(
                        "type" to entity.type.key.key,
                        "name" to (custom?.takeIf { it.isNotBlank() } ?: entity.name),
                        "uuid" to entity.uniqueId.toString(),
                        "distance" to String.format(Locale.US, "%.1f", dist),
                        "x" to loc.blockX.toString(),
                        "y" to loc.blockY.toString(),
                        "z" to loc.blockZ.toString(),
                        "world" to loc.world.name,
                    )
                }
                .sortedBy { it.first }
                .take(LIMIT)
                .map { it.second }
                .toList()
        }
    }
}
