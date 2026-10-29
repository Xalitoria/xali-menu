package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.util.message.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServerLoadEvent

/**
 * Reads the menus once every plugin has enabled, so the actions and conditions they added are there to parse them with,
 * and takes away what a plugin added when it is disabled.
 */
class PluginListener(private val plugin: XaliMenu) : Listener {
    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        if (!plugin.menus.isLoaded) loadMenus()
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === plugin) return
        // When the server is stopping every plugin is disabled in turn; reading the menus for each would be for nothing.
        if (plugin.registry.removeAll(event.plugin) && !plugin.server.isStopping) {
            plugin.logger info "${event.plugin.name} was disabled; the menus are read again without what it added."
            loadMenus()
        }
    }

    fun loadMenus() {
        plugin.logger pass plugin.messageConfig.data.commands.menu.loaded(plugin.menus.load())
    }
}
