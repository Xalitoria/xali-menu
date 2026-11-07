package net.xalitoria.menu

/**
 * A list of things that a paged menu (`pages` in a menu file) shows, one page at a time: `{ type = "players" }`. Each
 * element is a map of named values, and the item that is drawn for it can use them as `{name}` in its text, its
 * material and its commands (`material = "head:{name}"`). Add your own with [XaliMenuApi.registerCatalog], the same way
 * as an [Action].
 *
 * ```kotlin
 * class Homes : Catalog {
 *     override fun elements(ctx: MenuContext): List<Map<String, String>> =
 *         MyPlugin.homes(ctx.player).map { mapOf("name" to it.name, "world" to it.world) }
 * }
 *
 * XaliMenuApi.get().registerCatalog(plugin, "myplugin:homes") { Homes() }
 * ```
 *
 * The menu adds `index` (the element's place in the list, from 1) to every element. Say which values the elements have, with an
 * example of each, when registering: `registerCatalog(plugin, "myplugin:homes", mapOf("name" to "home1", "world" to "world")) { Homes() }`,
 * so that the editor can offer them.
 */
interface Catalog {
    /**
     * The elements now, in the order they are to be listed unless the menu sorts them. It is called on the region thread
     * of [MenuContext.player], on every refresh of the menu, so it must be cheap and must not change anything. The
     * values reach text the way a menu's arguments do (`%` and control characters taken out), so they may come from
     * players.
     */
    fun elements(ctx: MenuContext): List<Map<String, String>>
}
