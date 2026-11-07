package net.xalitoria.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * The way into XaliMenu for other plugins, found in the services manager. List XaliMenu as a dependency of your plugin
 * so it is enabled first, and register what you add in `onEnable`: menus are read after every plugin has enabled, so
 * what you register is there to parse them. What a plugin registered goes when it is disabled.
 *
 * Everything an addon touches in this package is made of JDK, Bukkit, Adventure and this package's own types, and no
 * Kotlin ones (no function types, `KClass`, serializers or default constructor arguments). A plugin loaded with its own
 * copy of the Kotlin runtime, as every plugin that uses the library loader is, can therefore use it, and so can one
 * written in Java.
 */
interface XaliMenuApi {
    val variables: Variables

    /** The names of the menus that are loaded. */
    val menus: Set<String>

    /** Opens [menu] for [player] on the next tick of their region. Nothing happens if there is no such menu. */
    fun open(player: Player, menu: String, args: List<String>)

    fun open(player: Player, menu: String)

    /**
     * Adds an action type. [name] is what `type` says in menu files; it has the form `plugin:action` in lower case
     * (letters, digits and `_ - .` on each side), with your plugin's name before the colon so it cannot meet another
     * plugin's. [type] builds the action from the keys written next to `type`.
     */
    fun registerAction(owner: Plugin, name: String, type: ActionType)

    /** Adds a condition type, named and built the same way as an action's. */
    fun registerCondition(owner: Plugin, name: String, type: ConditionType)

    /** Adds a catalog type for paged menus, named and built the same way as an action's. Editors cannot tell what its elements hold; see the other overload. */
    fun registerCatalog(owner: Plugin, name: String, type: CatalogType)

    /**
     * Adds a catalog type and says what each of its elements holds: [values] maps the name of each value (`name`, as in `{name}`) to
     * an example of it (`Notch`). The editor offers these names for the item template and draws the template with the examples, so
     * a menu can be built without knowing the catalog. Nothing else reads them.
     */
    fun registerCatalog(owner: Plugin, name: String, values: Map<String, String>, type: CatalogType)

    /**
     * Adds an activator type, named like an action's. [type] builds the activator from the keys written next to `type`; the
     * returned source is how you tell the menus that a player did what it stands for.
     */
    fun registerActivator(owner: Plugin, name: String, type: ActivatorType): ActivatorSource

    fun registerItemSource(owner: Plugin, source: ItemSource)

    companion object {
        /** The API of the XaliMenu plugin that is running. */
        @JvmStatic
        fun get(): XaliMenuApi = Bukkit.getServicesManager().load(XaliMenuApi::class.java)
            ?: error("XaliMenu is not enabled; add it as a dependency of your plugin")
    }
}
