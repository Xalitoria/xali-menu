package mc.xalitoria.menu.paper.runtime

import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.bedrock.BedrockPlanner
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MenuListener(private val plugin: XaliMenu) : Listener {
    /** Per-player click timestamp (ns), so reopening a menu cannot reset `click-cooldown`. */
    private val lastClick = ConcurrentHashMap<UUID, Long>()

    /** Players who already have a next-tick inventory resync scheduled, preventing packet-flood amplification. */
    private val resyncPending = ConcurrentHashMap.newKeySet<UUID>()

    /** Schedules at most one next-tick `player.updateInventory()` (and optional display-item sweep) per player. */
    private fun scheduleResync(player: Player, sweep: Boolean = false) {
        val id = player.uniqueId
        if (!resyncPending.add(id)) return
        player.scheduler.runDelayed(plugin, {
            resyncPending.remove(id)
            if (!player.isOnline) return@runDelayed
            if (sweep || player.gameMode == GameMode.CREATIVE) MenuClicks.sweepDisplayItems(player)
            player.updateInventory()
        }, { resyncPending.remove(id) }, 1)
    }

    private fun isDepositPassThrough(view: MenuView, top: Boolean, click: ClickType): Boolean =
        !top && !view.drawsInventory && click in OFFERING && view.hasAcceptors()

    // HIGHEST, so that a plugin that un-cancels a click at a lower priority cannot let a vanilla move happen in a menu.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val view = event.inventory.getHolder(false) as? MenuView
        if (view == null) {
            // Even outside a menu, never allow a leaked menu display stack to be moved.
            if (MenuClicks.isMenuDisplayItem(event.currentItem) || MenuClicks.isMenuDisplayItem(event.cursor)) {
                event.isCancelled = true
                (event.whoClicked as? Player)?.let { scheduleResync(it, sweep = true) }
            }
            return
        }
        // Nothing moves in or out of a menu, whichever inventory the click was in (shift-click would carry items over).
        val resultBefore = event.result
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val top = event.clickedInventory === event.inventory
        val creative = player.gameMode == GameMode.CREATIVE
        val legacy = Clients.isLegacyInventoryClient(player)
        // The rows of the player's own inventory are the menu's too when it draws in them.
        if (!top && event.clickedInventory !== player.inventory) {
            if (MenuClicks.needsResyncAfterCancel(event.click, creative, legacy)) scheduleResync(player, sweep = creative)
            return
        }
        if (player.uniqueId != view.player.uniqueId) {
            scheduleResync(player, sweep = creative)
            return
        }
        // With a slot that takes the cursor's item, picking an item up and putting it down in the player's own inventory is
        // left alone: it only moves things within it, and it is how an item gets to the cursor. Nothing else is.
        if (isDepositPassThrough(view, top, event.click)) {
            if (MenuClicks.isMenuDisplayItem(event.currentItem) || MenuClicks.isMenuDisplayItem(event.cursor)) {
                scheduleResync(player, sweep = true)
                return
            }
            // Left as it was when it came, so what another plugin cancelled stays cancelled.
            event.result = resultBefore
            return
        }

        if (MenuClicks.needsResyncAfterCancel(event.click, creative, legacy)) {
            scheduleResync(player, sweep = creative)
        }

        val now = System.nanoTime()
        val prev = lastClick[player.uniqueId]
        val cooldownNs = plugin.config.data.clickCooldown * 1_000_000
        if (prev != null && cooldownNs > 0 && now - prev < cooldownNs) return
        lastClick[player.uniqueId] = now

        // A slot that takes an item: the one on the cursor, or the one shift-clicked in the player's inventory. Whatever is
        // offered is looked up on the server, and nothing leaves the player until the slot's actions have run.
        if (top) {
            val accept = view.itemAt(event.slot)?.accept
            if (accept != null && !view.drawsInventory && event.click in OFFERING && !player.itemOnCursor.isEmpty) {
                plugin.deposits.offer(accept, CursorOffer(player), view.contextAt(event.slot))
                return
            }
        } else if (event.click.isShiftClick && !view.drawsInventory && event.slot in 0..35) {
            val offer = SlotOffer(player, event.slot)
            val taker = view.acceptors().firstOrNull { plugin.deposits.fits(it.first.accept!!, offer, it.second) }
            if (taker != null) plugin.deposits.offer(taker.first.accept!!, offer, taker.second)
            return
        }

        // Only the 5 schema button clicks activate handlers; double-click, hotbar swap, drop, offhand and creative clicks stay cancelled.
        if (event.click !in MenuClicks.HANDLED_CLICKS) return

        val item = (if (top) view.itemAt(event.slot) else view.inventoryItemAt(event.slot)) ?: return
        val ctx = if (top) view.contextAt(event.slot) else view.ctx
        val bedrockCfg = plugin.config.data.bedrock
        val actionPicker = view.menu.bedrock?.actionPicker ?: true
        val choices = if (view.profile.isBedrock && bedrockCfg.clickFallback) BedrockPlanner.choicesFor(item) else emptyList()

        // Touch, controller and Pocket UI players cannot right-click or shift-click easily; offer a choice form when an item has multiple click handlers.
        if (view.profile.prefersForm && bedrockCfg.clickFallback && actionPicker && choices.size > 1 && plugin.bedrockForms.available) {
            player.scheduler.run(plugin, {
                player.closeInventory()
                plugin.bedrockForms.showActionPicker(player, item, ctx, choices)
            }, null)
            return
        }

        val handler = MenuClicks.resolveHandler(item.click, event.click) ?: run {
            if (!view.profile.isBedrock || !bedrockCfg.clickFallback || choices.isEmpty()) return
            if (choices.size > 1 && actionPicker && plugin.bedrockForms.available) {
                player.scheduler.run(plugin, {
                    player.closeInventory()
                    plugin.bedrockForms.showActionPicker(player, item, ctx, choices)
                }, null)
                return
            }
            choices.first().handler
        }
        if (!handler.require.passes(ctx, run = true)) return
        plugin.actions.run(handler.actions, ctx)
    }

    /** Stops creative-mode clients from spawning or keeping cloned copies of menu display items. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCreative(event: InventoryCreativeEvent) {
        val inMenu = event.inventory.getHolder(false) is MenuView
        val marked = MenuClicks.isMenuDisplayItem(event.cursor) || MenuClicks.isMenuDisplayItem(event.currentItem)
        if (!inMenu && !marked) return
        event.isCancelled = true
        if (MenuClicks.isMenuDisplayItem(event.cursor)) event.cursor = ItemStack.empty()
        (event.whoClicked as? Player)?.let { scheduleResync(it, sweep = true) }
    }

    /** Detects if another plugin un-cancelled a menu click outside the deposit pass-through and resyncs/sweeps immediately. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClickMonitor(event: InventoryClickEvent) {
        if (event.isCancelled) return
        val view = event.inventory.getHolder(false) as? MenuView ?: return
        val top = event.clickedInventory === event.inventory
        if (isDepositPassThrough(view, top, event.click)) return
        val player = event.whoClicked as? Player ?: return
        plugin.logger.warning("Another plugin un-cancelled a ${event.click} click in menu '${view.name}' for ${player.name}; resyncing inventory.")
        scheduleResync(player, sweep = true)
    }

    private companion object {
        /** The clicks that put the cursor's item down. */
        val OFFERING = setOf(ClickType.LEFT, ClickType.RIGHT)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.getHolder(false) !is MenuView) return
        event.isCancelled = true
        (event.whoClicked as? Player)?.let { scheduleResync(it, sweep = it.gameMode == GameMode.CREATIVE) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDragMonitor(event: InventoryDragEvent) {
        if (event.isCancelled) return
        val view = event.inventory.getHolder(false) as? MenuView ?: return
        val player = event.whoClicked as? Player ?: return
        plugin.logger.warning("Another plugin un-cancelled an inventory drag in menu '${view.name}' for ${player.name}; resyncing inventory.")
        scheduleResync(player, sweep = true)
    }

    /** Recipe-book auto-fill moves items without firing InventoryClickEvent; block it while any menu is open. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRecipeBookClick(event: PlayerRecipeBookClickEvent) {
        if (plugin.menus.view(event.player) == null) return
        event.isCancelled = true
        scheduleResync(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (event.inventory.getHolder(false) is MenuView) event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        if (event.inventory.getHolder(false) is MenuView) event.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        if (event.inventory.getHolder(false) is MenuView) event.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareGrindstone(event: PrepareGrindstoneEvent) {
        if (event.inventory.getHolder(false) is MenuView) event.result = null
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (MenuClicks.isMenuDisplayItem(event.itemDrop.itemStack)) {
            event.itemDrop.remove()
            scheduleResync(event.player, sweep = true)
        }
    }

    @EventHandler
    fun onPickup(event: EntityPickupItemEvent) {
        if (MenuClicks.isMenuDisplayItem(event.item.itemStack)) {
            event.isCancelled = true
            event.item.remove()
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val view = event.inventory.getHolder(false) as? MenuView ?: return
        val player = event.player as? Player ?: return
        plugin.menus.closed(player, view)
        // Resync on the next tick to repair any ghost item from a click right before Escape (and sweep leaked display stacks).
        if (!view.drawsInventory) scheduleResync(player, sweep = true)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        MenuClicks.sweepDisplayItems(event.player)
        plugin.heads.remember(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        lastClick.remove(id)
        resyncPending.remove(id)
        plugin.heads.remember(event.player)
        plugin.menus.left(event.player)
    }

    /**
     * Opens a menu for commands that cannot be registered as commands: the ones of several words, and the ones of menus
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
