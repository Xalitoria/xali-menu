package mc.xalitoria.menu.paper.extension

import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.logging.Logger

/** The server behind [Extensions]: XaliMenu itself, its plugin manager and its schedulers. */
class PluginHost(private val menu: XaliMenu) : Extensions.Host {
    override val plugin: Plugin get() = menu

    override val folder: Path get() = menu.dataPath.resolve("extensions")

    override val logger: Logger get() = menu.logger

    override val parent: ClassLoader get() = XaliMenu::class.java.classLoader

    override fun enabledPlugins(): Map<String, ClassLoader> = menu.server.pluginManager.plugins
        .filter { it.isEnabled && it !== menu }
        .associate { it.name.lowercase() to it.javaClass.classLoader }

    override fun listen(listener: Listener) = menu.server.pluginManager.registerEvents(listener, menu)

    override fun unlisten(listener: Listener) = HandlerList.unregisterAll(listener)

    override fun fire(name: String, player: Player, values: Map<String, String>): Boolean = menu.activators.onAdded(name, player, values)

    /** A menu that was open when its extension went may still click an action of it, so its classes are kept for a while. */
    override fun release(loader: AutoCloseable) {
        if (menu.isEnabled && !menu.server.isStopping) menu.server.globalRegionScheduler.runDelayed(menu, { runCatching { loader.close() } }, RELEASE_TICKS)
        else runCatching { loader.close() }
    }

    private companion object {
        /** Five minutes. */
        const val RELEASE_TICKS = 6000L
    }
}
