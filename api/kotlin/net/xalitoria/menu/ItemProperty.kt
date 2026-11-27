package net.xalitoria.menu

import org.bukkit.inventory.ItemStack

/**
 * A custom property on a menu item, added by an addon or extension with [XaliMenuApi.registerItemProperty] or
 * [ExtensionContext.registerItemProperty]. In a menu file it is written directly on the item under its namespaced key:
 *
 * ```hocon
 * items.tools {
 *     material = chest
 *     slot = 0
 *     "essentials:kit-preview" { kit = "tools" }
 * }
 * ```
 *
 * A single scalar or list value (`"myplugin:tag" = "boss"`) is passed to [ItemPropertyType.create] as a [Settings]
 * with one key named `value`.
 *
 * Implement [ValidatedItemProperty] instead when the property also has load-time checks to report through
 * `/xmenu validate` without refusing the menu.
 */
fun interface ItemProperty {
    /**
     * Shapes [item] for [MenuContext.player], after XaliMenu has applied the built-in item properties. Called on the
     * player's region thread when the item is built (and on every refresh for an item marked `update`), so it must be
     * cheap. Use [MenuContext.parse] on text from settings that may contain placeholders or `{name}` values.
     */
    fun apply(item: ItemStack, ctx: MenuContext)
}

/**
 * An [ItemProperty] that also checks its own configuration at menu load time and on `/xmenu validate`, reporting
 * non-fatal problems without refusing the menu. For a key that makes the property unusable at all, throw an
 * [IllegalArgumentException] from [ItemPropertyType.create] instead.
 */
interface ValidatedItemProperty : ItemProperty {
    /** Problems with this property that do not depend on who views the item; empty when valid. */
    fun validate(): List<String>
}
