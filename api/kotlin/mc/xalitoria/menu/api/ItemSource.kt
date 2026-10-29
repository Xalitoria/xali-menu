package mc.xalitoria.menu.api

import org.bukkit.inventory.ItemStack

/**
 * A plugin that makes custom items. An item whose `material` is written `<prefix>:<id>` gets its stack from the source
 * with that [prefix]; add one with [XaliMenuApi.registerItemSource].
 */
interface ItemSource {
    /** What comes before the colon, in lower case. */
    val prefix: String

    /** The item with [id], or null if there is none (or the plugin hasn't loaded its items yet). Called on any thread. */
    fun item(id: String): ItemStack?
}
