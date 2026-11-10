package mc.xalitoria.menu.paper.screen

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** The index in a window of [slot] of a player's inventory (0 to 8 the hotbar, 9 to 35 the rows above it), when the window's own slots are [top]. */
fun windowIndex(top: Int, slot: Int): Int = top + if (slot >= 9) slot - 9 else 27 + slot

/**
 * What a menu shows in the bottom rows of one player's window, in place of the player's own items. Read on the network threads (which rewrite the
 * packets that carry the window's items) and set on the player's own thread, hence the volatile fields, each replaced as a whole.
 */
class Overlay(val top: Int) {
    /** The id the client gives the window, once known; [UNBOUND] until the packet that opens it has gone by. */
    @Volatile
    var windowId = UNBOUND

    /** What goes in each slot of the player's inventory, as the stacks of the server. */
    @Volatile
    var items: Map<Int, ItemStack> = emptyMap()

    /** The same, as the packets of the library in use carry them, by index in the window. */
    @Volatile
    var packed: Map<Int, Any> = emptyMap()

    /** Replaces the items of [contents], the list a packet carries for the window, by those of the overlay. */
    @Suppress("UNCHECKED_CAST")
    fun <T> rewrite(contents: MutableList<T>) {
        for (entry in packed) if (entry.key < contents.size) contents[entry.key] = entry.value as T
    }

    /** The replacement for the item of [index] in the window, if the overlay has one. */
    @Suppress("UNCHECKED_CAST")
    fun <T> replacement(index: Int): T? = packed[index] as T?

    companion object {
        const val UNBOUND = -1
    }
}

/** What a library that can rewrite the packets of a window does: PacketEvents or ProtocolLib. */
interface ScreenBackend {
    val name: String

    /** The form in which the library's packets carry [stack]. */
    fun pack(stack: ItemStack): Any

    /** Stops listening. */
    fun stop()
}

/**
 * Lets a menu draw in the bottom rows of its window, the player's own inventory, without touching that inventory. The window's contents are what the
 * server sends the client, so the packets that carry them are rewritten on the way out: the server's inventory keeps what the player has, and nothing
 * the menu draws can be lost, taken or duplicated, since it only ever exists in a packet. Which library does it is [backend]; with none, nothing is drawn.
 */
class Screens(private val plugin: XaliMenu) {
    private var backend: ScreenBackend? = null

    /** Overlays of menus that are opening, until the packet that opens their window says which window it is. */
    private val pending = ConcurrentHashMap<UUID, Overlay>()
    private val bound = ConcurrentHashMap<UUID, Overlay>()
    private var warned = false

    val available: Boolean get() = backend != null

    /** The name of the library in use, or null. */
    val name: String? get() = backend?.name

    /** Picks the library: PacketEvents when it is enabled, else ProtocolLib. */
    fun start() {
        val plugins = plugin.server.pluginManager
        backend = try {
            when {
                plugins.isPluginEnabled("packetevents") -> PacketEventsBackend(plugin, this)
                plugins.isPluginEnabled("ProtocolLib") -> ProtocolLibBackend(plugin, this)
                else -> null
            }
        } catch (e: Throwable) {
            plugin.logger.warning("Drawing in the player's inventory is not available: ${e.message ?: e::class.simpleName}")
            null
        }
    }

    fun stop() {
        backend?.stop()
        backend = null
        pending.clear()
        bound.clear()
    }

    /** Says once that a menu wants to draw in the inventory and nothing can. */
    fun unavailable(menu: String) {
        if (warned) return
        warned = true
        plugin.logger.warning("Menu '$menu' draws in the player's inventory, which needs PacketEvents or ProtocolLib: install one of them, or the rows are left as they are.")
    }

    /** The menu of [player] is about to open a window of [top] slots of its own: its overlay is ready for the packet that opens it. */
    fun expect(player: Player, top: Int) {
        bound.remove(player.uniqueId)
        pending[player.uniqueId] = Overlay(top)
    }

    /** Shows [items] (by slot of the player's inventory) in the rows of the window; nothing at all for an empty map. */
    fun show(player: Player, items: Map<Int, ItemStack>) {
        val backend = backend ?: return
        val overlay = pending[player.uniqueId] ?: bound[player.uniqueId] ?: return
        overlay.items = items
        overlay.packed = items.entries.associate { windowIndex(overlay.top, it.key) to backend.pack(it.value) }
        // A window the client has is sent its contents again, through the rewrite; one that is opening gets them with it.
        if (overlay.windowId != Overlay.UNBOUND) player.updateInventory()
    }

    /**
     * The player's menu is gone. [resync] sends the inventory again once the window has gone: the client keeps what a window was told about
     * the slots of the inventory, so without it the hotbar would show the menu's items until the next change.
     */
    fun end(player: Player, resync: Boolean = true) {
        val had = bound.remove(player.uniqueId) != null || pending.remove(player.uniqueId) != null
        if (had && resync) player.scheduler.runDelayed(plugin, { player.updateInventory() }, null, 1)
    }

    // ---- what the libraries call, from the network threads

    /** A window is opened for [id]'s player: it is the one of the pending menu, or some other window, which the overlay no longer applies to. */
    fun opened(id: UUID, windowId: Int) {
        val own = pending.remove(id)
        if (own != null) {
            own.windowId = windowId
            bound[id] = own
        } else {
            bound[id]?.windowId = Overlay.UNBOUND
        }
    }

    /** The overlay that applies to the window [windowId] of [id]'s player, if there is one. */
    fun overlay(id: UUID, windowId: Int): Overlay? = bound[id]?.takeIf { it.windowId == windowId && it.packed.isNotEmpty() }
}
