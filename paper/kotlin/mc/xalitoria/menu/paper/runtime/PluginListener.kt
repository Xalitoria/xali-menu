package mc.xalitoria.menu.paper.runtime

import com.xalitoria.util.message.*
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent

/**
 * Registers the extensions and reads the menus once every plugin has enabled, so the actions and conditions that plugins and
 * extensions added are there to parse them with, and takes away what a plugin added when it is disabled. An extension that waits
 * for a plugin is registered when it enables, and goes when it is disabled.
 */
class PluginListener(private val plugin: XaliMenu) : Listener {
    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        if (!plugin.menus.isLoaded) start()
    }

    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin === plugin || !plugin.menus.isLoaded) return
        if (plugin.extensions.pluginEnabled(event.plugin.name)) {
            plugin.logger info "${event.plugin.name} was enabled; its extensions are registered and the menus are read again."
            loadMenus()
        }
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === plugin) return
        // When the server is stopping every plugin is disabled in turn; reading the menus for each would be for nothing.
        if (plugin.server.isStopping) return
        val added = plugin.registry.removeAll(event.plugin)
        val extended = plugin.extensions.pluginDisabled(event.plugin.name)
        if (added || extended) {
            plugin.logger info "${event.plugin.name} was disabled; the menus are read again without what it added."
            loadMenus()
        }
    }

    /** The first reading: the extensions first, then the menus that use what they added. */
    fun start() {
        plugin.extensions.load()
        loadMenus()
    }

    fun loadMenus() {
        plugin.logger pass plugin.messageConfig.data.commands.menu.loaded(plugin.menus.load())
    }
}
