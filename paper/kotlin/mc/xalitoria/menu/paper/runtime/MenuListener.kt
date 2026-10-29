package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.api.model.Handler
import mc.xalitoria.menu.api.model.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent

class MenuListener(private val plugin: XaliMenu) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val view = event.inventory.getHolder(false) as? MenuView ?: return
        // Nothing moves in or out of a menu, whichever inventory the click was in (shift-click would carry items over).
        event.isCancelled = true
        if (event.clickedInventory !== event.inventory) return
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != view.player.uniqueId) return

        val now = System.nanoTime()
        if (view.lastClick != 0L && now - view.lastClick < plugin.config.data.clickCooldown * 1_000_000) return
        view.lastClick = now

        val item = view.itemAt(event.slot) ?: return
        val handler = handler(item, event.click) ?: return
        if (!handler.require.passes(view.ctx, run = true)) return
        plugin.actions.run(handler.actions, view.ctx)
    }

    /** The handler for this kind of click, or the one for any click when the item has none of its own. */
    private fun handler(item: Item, click: ClickType): Handler? {
        val clicks = item.click
        val own = when (click) {
            ClickType.LEFT -> clicks.left
            ClickType.RIGHT -> clicks.right
            ClickType.SHIFT_LEFT -> clicks.shiftLeft
            ClickType.SHIFT_RIGHT -> clicks.shiftRight
            ClickType.MIDDLE -> clicks.middle
            else -> null
        }
        return own ?: clicks.any
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.getHolder(false) is MenuView) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val view = event.inventory.getHolder(false) as? MenuView ?: return
        plugin.menus.closed(event.player as? Player ?: return, view)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.menus.left(event.player)
    }

    /**
     * Opens a menu for commands that can't be registered as commands: the ones of several words, and the ones of menus
     * that ask not to be registered. A command that is registered runs normally.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val typed = event.message.removePrefix("/").trim()
        val lower = typed.lowercase()
        val match = plugin.menus.intercepted().entries
            .filter { lower == it.key || lower.startsWith("${it.key} ") }
            .maxByOrNull { it.key.length } ?: return
        event.isCancelled = true
        val args = typed.substring(match.key.length).trim().split(' ').filter { it.isNotEmpty() }
        plugin.menus.open(event.player, match.value, args)
    }
}
