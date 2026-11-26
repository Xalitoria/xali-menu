package mc.xalitoria.menu.paper.hook

import com.xalitoria.util.message.info
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.runtime.clearTextCache
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/**
 * ItemsAdder loads custom items and font images asynchronously after plugins enable (and again on `/iareload`).
 * Clears cached titles/item warnings and reloads menus once ItemsAdder finishes loading so custom items and glyphs
 * do not stay stuck as barriers or unexpanded tags.
 */
class ItemsAdderListener(private val plugin: XaliMenu) : Listener {
    @EventHandler
    fun onItemsAdderLoad(event: ItemsAdderLoadDataEvent) {
        clearTextCache()
        plugin.items.clearWarnings()
        plugin.editor.api.notifyPackUpdated("ItemsAdder")
        if (plugin.menus.isLoaded) {
            plugin.logger info "ItemsAdder data loaded (${event.cause.name.lowercase()}); refreshing menus."
            plugin.menus.load()
        }
    }
}
