package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import mc.xalitoria.menu.paper.XaliMenu
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.Animation
import net.xalitoria.menu.model.Pages
import mc.xalitoria.menu.paper.util.legacy
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
    /** The items drawn in the player's own inventory, by the slot of that inventory. */
    private val inventoryBySlot: Map<Int, List<Pair<String, Item>>> = emptyMap(),
) : InventoryHolder {
    /** What goes into a slot of a paged menu: the item, the context its text and clicks use, and the element it is for. */
    private class Placed(val item: Item, val ctx: Context, val element: Map<String, String>?)

    private lateinit var inventory: Inventory
    private lateinit var shown: Array<Item?>
    private lateinit var shownElement: Array<Map<String, String>?>
    private lateinit var contexts: Array<Context?>

    /** What the menu has put in each slot of the player's own inventory (0 to 35). */
    private val shownInventory = arrayOfNulls<Item>(36)
    private var refresh: ScheduledTask? = null

    /** What the frames of the menu's animation have drawn so far, by slot, in the order the slot's items are tried. */
    private var overlay: Map<Int, List<Pair<String, Item>>> = emptyMap()
    private var animation: ScheduledTask? = null
    private var cursor: FrameCursor? = null

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

    /** Whether the title holds anything that can change while the menu is open: a placeholder, or a `{page}` and the like. */
    private val dynamicTitle = '%' in menu.title || '{' in menu.title

    /** The title the player's client has; null until [title] is asked for. */
    private var shownTitle: String? = null

    /** When the player last clicked, in nanoseconds of [System.nanoTime]. */
    var lastClick = 0L

    /** The context of the menu as a whole, which knows the page. */
    var ctx: Context = opened
        private set

    val player: Player get() = ctx.player

    override fun getInventory(): Inventory = inventory

    /**
     * The title to open the inventory with. A paged menu works out its first page first, so the title can already show it.
     */
    fun title(): String {
        menu.pages?.let { layout(it) }
        return ctx.legacy(menu.title).also { shownTitle = it }
    }

    fun attach(inventory: Inventory) {
        this.inventory = inventory
        shown = arrayOfNulls(inventory.size)
        shownElement = arrayOfNulls(inventory.size)
        contexts = arrayOfNulls(inventory.size)
    }

    /** The item that is in [slot] now, if there is one. */
    fun itemAt(slot: Int): Item? = shown.getOrNull(slot)

    /** The item the menu has put in [slot] of the player's own inventory, if there is one. */
    fun inventoryItemAt(slot: Int): Item? = shownInventory.getOrNull(slot)

    /**
     * Why each slot shows what it shows, one line per thing to say: the items that claim it, which of them is drawn, and for
     * each the checks of its `view` gate. A slot that nothing claims is left out.
     */
    fun inspect(): List<String> = buildList {
        for (slot in 0 until inventory.size) {
            val candidates = overlay[slot].orEmpty().map { "frame" to it } + bySlot[slot].orEmpty().map { "menu" to it }
            val element = shownElement[slot]
            if (candidates.isEmpty() && element == null) continue
            val drawn = shown[slot]
            add("slot $slot: " + when {
                drawn == null -> "empty"
                element != null -> "an element of the pages ${element.entries.joinToString { it.key + "=" + it.value }}"
                else -> "'" + (candidates.firstOrNull { it.second.second === drawn }?.second?.first ?: "?") + "'"
            })
            for ([source, candidate] in candidates) {
                val [key, item] = candidate
                val passes = item.view.passes(ctx, run = false)
                add("  $source '$key' (priority ${item.priority}): view ${if (passes) "passes" else "does not pass"}${if (drawn === item) ", drawn" else ""}")
                item.view.explain(ctx).forEach { add("    $it") }
            }
        }
        for (entry in inventoryBySlot.entries.sortedBy { it.key }) {
            val drawn = shownInventory[entry.key]
            add("inventory slot ${entry.key}: " + (drawn?.let { item -> "'" + (entry.value.firstOrNull { it.second === item }?.first ?: "?") + "'" } ?: "empty"))
            for ([key, item] in entry.value) {
                val passes = item.view.passes(ctx, run = false)
                add("  inventory '$key' (priority ${item.priority}): view ${if (passes) "passes" else "does not pass"}${if (drawn === item) ", drawn" else ""}")
                item.view.explain(ctx).forEach { add("    $it") }
            }
        }
    }

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
            // What a frame drew comes first, then the elements of the page, then the menu's own items.
            val framed = overlay[slot]?.firstOrNull { it.second.view.passes(ctx, run = false) }?.second
            val entry = if (framed == null) placed[slot] else null
            val chosen: Item?
            val slotCtx: Context
            if (framed != null) {
                chosen = framed
                slotCtx = ctx
            } else if (entry != null) {
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
        drawInventory(rebuild)
        if (dynamicTitle) retitle()
    }

    /** Puts the right item in each slot of the player's own inventory that the menu draws in, as [draw] does for the menu's. */
    private fun drawInventory(force: Boolean) {
        for (entry in inventoryBySlot) {
            val slot = entry.key
            val chosen = entry.value.firstOrNull { it.second.view.passes(ctx, run = false) }?.second
            if (chosen == null) {
                if (shownInventory[slot] != null) {
                    plugin.lending.put(player, slot, null)
                    shownInventory[slot] = null
                }
            } else if (chosen !== shownInventory[slot] || chosen.update || force) {
                plugin.lending.put(player, slot, plugin.lending.mark(plugin.items.build(chosen, ctx) { invalidateInventory(slot) }))
                shownInventory[slot] = chosen
            }
        }
    }

    /**
     * Gives the open inventory the title as it reads now, if that is not what the client has. Paper changes a title by
     * sending the screen again with the new text, which is done only when the text differs, since it makes the client
     * rebuild the window (and put the mouse back in the middle).
     */
    @Suppress("DEPRECATION")
    private fun retitle() {
        val wanted = ctx.legacy(menu.title)
        if (wanted == shownTitle) return
        val open = player.openInventory
        if (open.topInventory !== inventory) return // not open yet: the title it opens with is [title]
        shownTitle = wanted
        try {
            open.setTitle(wanted)
        } catch (e: IllegalArgumentException) {
            plugin.logger.warning("The title of menu '$name' can't be changed: ${e.message}")
            shownTitle = null
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
        val layout = layoutPage(listed, pages.listed(), pages.sort, pages.descending, page) { pages.item.view.passes(opened.with(it), run = false) }
        page = layout.state.page
        pageValues = layout.state.values()
        ctx = opened.with(pageValues)

        if (layout.empty) return pages.empty?.let { mapOf(pages.listed().first() to Placed(it, ctx, null)) } ?: emptyMap()
        return layout.placed.associate { [slot, element] -> slot to Placed(pages.item, ctx.with(element), element) }
    }

    /** Moves to the page [value] pages on, or to page [value] (from 1) if not [relative], and draws it. */
    fun turn(relative: Boolean, value: Int) {
        if (menu.pages == null) return
        val target = if (relative) page.toLong() + value else value.toLong() - 1
        page = target.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        draw()
    }

    /** A variable this menu shows has changed: draws again, from the player's thread. */
    fun variablesChanged() {
        player.scheduler.run(plugin, { if (plugin.menus.view(player) === this) draw() }, null)
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

    /** Has [slot] of the player's inventory built again at the next [draw], from the player's thread. */
    private fun invalidateInventory(slot: Int) {
        player.scheduler.run(plugin, {
            if (plugin.menus.view(player) === this) {
                shownInventory[slot] = null
                draw()
            }
        }, null)
    }

    /** Draws again every [ticks] while the menu is open. */
    fun startRefresh(ticks: Long) {
        refresh = player.scheduler.runAtFixedRate(plugin, { draw() }, null, ticks, ticks)
    }

    /**
     * Starts the menu's animation, if it has one: its `on-start` runs, and the first frame plays after its delay. Each
     * frame schedules the next one when it has played, so a frame comes when its delay is up and not at the next tick of
     * a polling loop.
     */
    fun startAnimation() {
        val animation = menu.animation ?: return
        cursor = FrameCursor(animation.frames.size, animation.loop)
        plugin.actions.run(animation.onStart, ctx)
        schedule(animation, animation.frames.first().delay)
    }

    private fun schedule(animation: Animation, delay: kotlin.time.Duration) {
        val ticks = (delay.inWholeMilliseconds / 50).coerceAtLeast(1)
        this.animation = player.scheduler.runDelayed(plugin, { play(animation) }, null, ticks)
    }

    /** Plays the frame the cursor is at, then arranges for the next. */
    private fun play(animation: Animation) {
        val cursor = cursor ?: return
        if (plugin.menus.view(player) !== this) return
        val frame = animation.frames[cursor.index]
        // A gate that fails only skips the frame; it is asked on every loop, so it runs no actions.
        if (frame.require.passes(ctx, run = false)) {
            plugin.actions.run(frame.onStart, ctx)
            overlay = (if (frame.clear) emptyMap() else overlay) + slotted(frame.items)
            draw()
            plugin.actions.run(frame.onEnd, ctx)
        }
        when (cursor.advance()) {
            FrameCursor.Step.ENDED -> {
                plugin.actions.run(animation.onEnd, ctx)
                return
            }
            FrameCursor.Step.WRAPPED -> {
                overlay = emptyMap()
                draw()
            }
            FrameCursor.Step.NEXT -> Unit
        }
        schedule(animation, animation.frames[cursor.index].delay)
    }

    /** Stops what runs on a schedule for this view: the refresh and the animation. */
    fun stopTasks() {
        refresh?.cancel()
        refresh = null
        animation?.cancel()
        animation = null
    }
}
