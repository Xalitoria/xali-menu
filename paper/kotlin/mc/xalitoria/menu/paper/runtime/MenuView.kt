package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.api.model.Item
import mc.xalitoria.menu.api.model.Menu
import mc.xalitoria.menu.api.model.Pages
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * One player's open menu: the inventory, and which item of the menu is in each slot. All of it is used on the player's
 * region thread only, except [pageValues], which placeholders read from anywhere.
 */
class MenuView(
    private val plugin: XaliMenu,
    val name: String,
    val menu: Menu,
    private val opened: Context,
    private val bySlot: Map<Int, List<Pair<String, Item>>>,
) : InventoryHolder {
    /** What goes into a slot of a paged menu: the item, the context its text and clicks use, and the element it is for. */
    private class Placed(val item: Item, val ctx: Context, val element: Map<String, String>?)

    private lateinit var inventory: Inventory
    private lateinit var shown: Array<Item?>
    private lateinit var shownElement: Array<Map<String, String>?>
    private lateinit var contexts: Array<Context?>
    private var refresh: ScheduledTask? = null

    /** The page asked for, from 0; [draw] brings it back to a page that exists. */
    private var page = 0

    /** The [pageValues] the last [draw] drew with. */
    private var lastDrawn: Map<String, String> = emptyMap()

    /** Set when the catalog of a paged menu failed, so the log gets it once and not on every refresh. */
    private var catalogFailed = false

    /** The `{page}` and so on of the current page; empty for a menu that has no pages. */
    @Volatile
    var pageValues: Map<String, String> = emptyMap()
        private set

    /** When the player last clicked, in nanoseconds of [System.nanoTime]. */
    var lastClick = 0L

    /** The context of the menu as a whole, which knows the page. */
    var ctx: Context = opened
        private set

    val player: Player get() = ctx.player

    override fun getInventory(): Inventory = inventory

    fun attach(inventory: Inventory) {
        this.inventory = inventory
        shown = arrayOfNulls(inventory.size)
        shownElement = arrayOfNulls(inventory.size)
        contexts = arrayOfNulls(inventory.size)
    }

    /** The item that is in [slot] now, if there is one. */
    fun itemAt(slot: Int): Item? = shown.getOrNull(slot)

    /** What a click in [slot] runs against: the context of the element the slot shows, or the menu's own. */
    fun contextAt(slot: Int): Context = contexts.getOrNull(slot) ?: ctx

    /**
     * Puts the right item in each slot. Of the items that claim a slot the first (lowest priority number) whose view gate
     * passes is shown, unless the slot lists an element of the menu's pages. A slot is only rebuilt when its item or element
     * changed, or when it is marked `update` or [force] is set, since building an item means parsing its text again.
     */
    fun draw(force: Boolean = false) {
        var rebuild = force
        var placed: Map<Int, Placed> = emptyMap()
        menu.pages?.let { pages ->
            placed = layout(pages)
            // The page, its count and the total are in the text of any item.
            if (pageValues != lastDrawn) {
                rebuild = true
                lastDrawn = pageValues
            }
        }
        for (slot in 0 until inventory.size) {
            val entry = placed[slot]
            val chosen: Item?
            val slotCtx: Context
            if (entry != null) {
                chosen = entry.item
                slotCtx = entry.ctx
            } else {
                chosen = bySlot[slot]?.firstOrNull { it.second.view.passes(ctx, run = false) }?.second
                slotCtx = ctx
            }
            if (chosen == null) {
                if (shown[slot] != null) {
                    inventory.setItem(slot, null)
                    shown[slot] = null
                    shownElement[slot] = null
                }
                contexts[slot] = null
            } else {
                contexts[slot] = slotCtx
                if (chosen !== shown[slot] || entry?.element != shownElement[slot] || chosen.update || rebuild) {
                    inventory.setItem(slot, plugin.items.build(chosen, slotCtx) { invalidate(slot) })
                    shown[slot] = chosen
                    shownElement[slot] = entry?.element
                }
            }
        }
    }

    /**
     * The elements of the current page, by the slot each goes into: the catalog's, laid out by [layoutPage] with the
     * template's `view` gate deciding which are listed.
     */
    private fun layout(pages: Pages): Map<Int, Placed> {
        val listed = try {
            pages.catalog.elements(opened)
        } catch (e: Exception) {
            if (!catalogFailed) plugin.logger.warning("The catalog of menu '$name' failed: ${e.message ?: e::class.simpleName}")
            catalogFailed = true
            emptyList()
        }
        val layout = layoutPage(listed, pages.slots, pages.sort, pages.descending, page) { pages.item.view.passes(opened.with(it), run = false) }
        page = layout.state.page
        pageValues = layout.state.values()
        ctx = opened.with(pageValues)

        if (layout.empty) return pages.empty?.let { mapOf(pages.slots.first() to Placed(it, ctx, null)) } ?: emptyMap()
        return layout.placed.associate { [slot, element] -> slot to Placed(pages.item, ctx.with(element), element) }
    }

    /** Moves to the page [value] pages on, or to page [value] (from 1) if not [relative], and draws it. */
    fun turn(relative: Boolean, value: Int) {
        if (menu.pages == null) return
        val target = if (relative) page.toLong() + value else value.toLong() - 1
        page = target.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        draw()
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
