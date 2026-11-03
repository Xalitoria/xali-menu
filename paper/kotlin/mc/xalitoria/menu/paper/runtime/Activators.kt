package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.api.model.Activator
import mc.xalitoria.menu.api.model.BlockClick
import mc.xalitoria.menu.api.model.Click
import mc.xalitoria.menu.api.model.TextMatch

/** An activator of the menu called [menu]. */
class Entry<A : Activator>(val menu: String, val activator: A)

/** The activators of all the loaded menus, by kind, in the order they are tried: by menu name, then as written. */
class ActivatorIndex(menus: Map<String, List<Activator>>) {
    private val all: List<Entry<Activator>> = menus.entries.sortedBy { it.key }.flatMap { entry -> entry.value.map { Entry(entry.key, it) } }

    val items: List<Entry<Activator.HeldItem>> = of()
    val joins: List<Entry<Activator.Join>> = of()
    val chats: List<Entry<Activator.Chat>> = of()
    val swaps: List<Entry<Activator.SwapHands>> = of()
    val blocks: List<Entry<Activator.Block>> = of()
    val entities: List<Entry<Activator.Entity>> = of()
    val npcs: List<Entry<Activator.Npc>> = of()
    val regionEnters: List<Entry<Activator.RegionEnter>> = of()
    val regionLeaves: List<Entry<Activator.RegionLeave>> = of()

    val isEmpty: Boolean get() = all.isEmpty()

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified A : Activator> of(): List<Entry<A>> = all.filter { it.activator is A } as List<Entry<A>>

    companion object {
        val EMPTY = ActivatorIndex(emptyMap())
    }
}

/** A key as the server writes it, without the `minecraft:` that people leave out. */
fun key(raw: String): String = raw.trim().lowercase().removePrefix("minecraft:")

private fun Boolean?.accepts(actual: Boolean) = this == null || this == actual

/**
 * Whether a player's click with an item in hand matches. [material] is the key of the item in hand, [name] its name as
 * plain text (null if it has none), [model] its custom model number; [left] says which click it was.
 */
fun Activator.HeldItem.matches(material: String, name: String?, model: Int?, left: Boolean, sneaking: Boolean, wantedName: (String) -> String): Boolean {
    val wantedMaterial = this.material
    val wantedItemName = this.name
    val wantedModel = this.model
    if (wantedMaterial != null && key(wantedMaterial) != material) return false
    if (wantedItemName != null && wantedName(wantedItemName) != name) return false
    if (wantedModel != null && wantedModel != model) return false
    if (click == Click.LEFT && !left || click == Click.RIGHT && left) return false
    return this.sneaking.accepts(sneaking)
}

/** Whether what the player said matches; [message] is plain text. */
fun Activator.Chat.matches(message: String): Boolean = when (match) {
    TextMatch.EQUALS -> message == text
    TextMatch.EQUALS_IGNORE_CASE -> message.equals(text, ignoreCase = true)
    TextMatch.CONTAINS -> message.contains(text, ignoreCase = true)
    TextMatch.STARTS_WITH -> message.startsWith(text, ignoreCase = true)
    TextMatch.REGEX -> Regex(text).matches(message)
}

fun Activator.SwapHands.matches(sneaking: Boolean): Boolean = this.sneaking.accepts(sneaking)

/**
 * Whether a click on a block matches. [click] is what happened, never [BlockClick.ANY]; [type] the block's key; [inTag]
 * says whether the block is in the tag with the given key, written like [key] (`buttons`, `mypack:shops`), for the `#...` entries of [Activator.Block.types].
 */
fun Activator.Block.matches(
    type: String, click: BlockClick, world: String, x: Int, y: Int, z: Int, sneaking: Boolean, inTag: (String) -> Boolean,
): Boolean {
    if (this.click != click && !(this.click == BlockClick.ANY && click != BlockClick.PHYSICAL)) return false
    if (!this.sneaking.accepts(sneaking)) return false
    at?.let { if (it.x != x || it.y != y || it.z != z || it.world != null && it.world != world) return false }
    if (types.isEmpty()) return true
    return types.any { entry ->
        if (entry.startsWith("#")) inTag(key(entry.removePrefix("#"))) else key(entry) == type
    }
}

/** Whether a click on an entity matches; [name] is its name as plain text, null if it has none. */
fun Activator.Entity.matches(type: String, name: String?, sneaking: Boolean, wantedName: (String) -> String): Boolean {
    if (types.isNotEmpty() && types.none { key(it) == type }) return false
    val wantedEntityName = this.name
    if (wantedEntityName != null && wantedName(wantedEntityName) != name) return false
    return this.sneaking.accepts(sneaking)
}

/** Whether a click on an NPC matches; [left] says which click it was, null for one that is both. */
fun Activator.Npc.matches(name: String, id: String, left: Boolean?, sneaking: Boolean): Boolean {
    if (left != null && (click == Click.LEFT && !left || click == Click.RIGHT && left)) return false
    if (npcs.isNotEmpty() && npcs.none { it.equals(name, ignoreCase = true) || it.equals(id, ignoreCase = true) }) return false
    return this.sneaking.accepts(sneaking)
}

/** Whether a region called [id] is one the activator is for; ids are lower case in WorldGuard, and people write them as they like. */
private fun matchesRegion(regions: List<String>, id: String) = regions.isEmpty() || regions.any { it.equals(id, ignoreCase = true) }

fun Activator.RegionEnter.matches(id: String) = matchesRegion(regions, id)

fun Activator.RegionLeave.matches(id: String) = matchesRegion(regions, id)

/** The regions a player entered and left, each in order of id. */
class RegionChange(entered: Collection<String>, left: Collection<String>) {
    val entered: List<String> = entered.sorted()
    val left: List<String> = left.sorted()
    val any: Boolean get() = this.entered.isNotEmpty() || this.left.isNotEmpty()

    companion object {
        /** Moving from standing in [before] to standing in [after], in the same world. */
        fun within(before: Set<String>, after: Set<String>) = RegionChange(after - before, before - after)

        /** Moving to another world: all the regions of the one are left and all those of the other entered. */
        fun between(before: Set<String>, after: Set<String>) = RegionChange(after, before)
    }
}
