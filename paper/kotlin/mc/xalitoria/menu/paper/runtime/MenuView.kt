package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.api.model.Item
import mc.xalitoria.menu.api.model.Menu
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * One player's open menu: the inventory, and which item of the menu is in each slot. All of it is used on the player's
 * region thread only.
 */
class MenuView(
    private val plugin: XaliMenu,
    val name: String,
    val menu: Menu,
    val ctx: Context,
    private val bySlot: Map<Int, List<Pair<String, Item>>>,
) : InventoryHolder {
    private lateinit var inventory: Inventory
    private lateinit var shown: Array<Item?>
    private var refresh: ScheduledTask? = null

    /** When the player last clicked, in nanoseconds of [System.nanoTime]. */
    var lastClick = 0L

    val player: Player get() = ctx.player

    override fun getInventory(): Inventory = inventory

    fun attach(inventory: Inventory) {
        this.inventory = inventory
        shown = arrayOfNulls(inventory.size)
    }

    /** The item that is in [slot] now, if there is one. */
    fun itemAt(slot: Int): Item? = shown.getOrNull(slot)

    /**
     * Puts the right item in each slot. Of the items that claim a slot the first (lowest priority number) whose view gate
     * passes is shown. A slot is only rebuilt when its item changed, or when it is marked `update` or [force] is set,
     * since building an item means parsing its text again.
     */
    fun draw(force: Boolean = false) {
        for (slot in 0 until inventory.size) {
            val candidates = bySlot[slot]
            val chosen = candidates?.firstOrNull { it.second.view.passes(ctx, run = false) }?.second
            if (chosen == null) {
                if (shown[slot] != null) {
                    inventory.setItem(slot, null)
                    shown[slot] = null
                }
            } else if (chosen !== shown[slot] || chosen.update || force) {
                inventory.setItem(slot, plugin.items.build(chosen, ctx) { invalidate(slot) })
                shown[slot] = chosen
            }
        }
    }

    /** Has [slot] built again at the next [draw], from the player's thread. */
    private fun invalidate(slot: Int) {
        player.scheduler.run(plugin, {
            if (plugin.menus.view(player) === this) {
                shown[slot] = null
                draw()
            }
        }, null)
    }

    /** Draws again every [ticks] while the menu is open. */
    fun startRefresh(ticks: Long) {
        refresh = player.scheduler.runAtFixedRate(plugin, { draw() }, null, ticks, ticks)
    }

    fun stopRefresh() {
        refresh?.cancel()
        refresh = null
    }
}
