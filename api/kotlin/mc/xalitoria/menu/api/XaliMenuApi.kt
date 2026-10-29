package mc.xalitoria.menu.api

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
     * (letters, digits and `_ - .` on each side), with your plugin's name before the colon so it can't meet another
     * plugin's. [type] builds the action from the keys written next to `type`.
     */
    fun registerAction(owner: Plugin, name: String, type: ActionType)

    /** Adds a condition type, named and built the same way as an action's. */
    fun registerCondition(owner: Plugin, name: String, type: ConditionType)

    fun registerItemSource(owner: Plugin, source: ItemSource)

    companion object {
        /** The API of the XaliMenu plugin that is running. */
        @JvmStatic
        fun get(): XaliMenuApi = Bukkit.getServicesManager().load(XaliMenuApi::class.java)
            ?: error("XaliMenu is not enabled; add it as a dependency of your plugin")
    }
}
