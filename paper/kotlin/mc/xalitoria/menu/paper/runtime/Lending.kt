package mc.xalitoria.menu.paper.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * What goes where when a menu is done with the slots of a player's own inventory it drew in: [originals] is what each slot
 * held before, [current] what it holds now. A slot that still holds the menu's item (or nothing) gets its original back; one
 * that holds anything else was filled by someone since, so its original is handed back to be given to the player instead.
 */
internal class Settlement<T : Any>(val restore: Map<Int, T?>, val give: List<T>)

internal fun <T : Any> settle(originals: Map<Int, T?>, current: (Int) -> T?, ours: (T) -> Boolean): Settlement<T> {
    val restore = mutableMapOf<Int, T?>()
    val give = mutableListOf<T>()
    for (entry in originals) {
        val now = current(entry.key)
        if (now == null || ours(now)) restore[entry.key] = entry.value
        else if (entry.value != null && now != entry.value) give += entry.value!!
    }
    return Settlement(restore, give)
}

/**
 * Lets a menu draw in a player's own inventory. The game has no separate inventory to show there, so the menu's items are put
 * into the real one, and what they replace is kept until the menu closes: in memory, and in the player's own data (so that it
 * is saved together with the inventory, and a crash in between cannot lose it). A player who joins with a keep is given it back.
 * Everything runs on the player's own thread.
 */
class Lending(plugin: XaliMenu) {
    private val ghost = NamespacedKey(plugin, "lent")
    private val keptSlots = NamespacedKey(plugin, "kept-slots")
    private val keptItems = NamespacedKey(plugin, "kept-items")

    /** What each player's slots held before a menu drew in them; absent for a player whose inventory is not lent. */
    private val held = ConcurrentHashMap<UUID, Map<Int, ItemStack?>>()

    /** Whether the menu of [player] has their inventory. */
    fun lent(player: Player): Boolean = held.containsKey(player.uniqueId)

    /** Whether [stack] is something a menu put in an inventory, which belongs in no player's hands. */
    fun ours(stack: ItemStack?): Boolean =
        stack != null && !stack.isEmpty && stack.persistentDataContainer.has(ghost, PersistentDataType.BYTE)

    /** [stack], marked as a menu's. */
    fun mark(stack: ItemStack): ItemStack = stack.also { it.editPersistentDataContainer { data -> data.set(ghost, PersistentDataType.BYTE, 1) } }

    /** Takes [slots] of the inventory of [player] for a menu, keeping what they hold. */
    fun begin(player: Player, slots: Set<Int>) {
        end(player)
        if (slots.isEmpty()) return
        val inventory = player.inventory
        val order = slots.sorted()
        val originals = order.associateWith { slot -> inventory.getItem(slot)?.takeUnless { it.isEmpty || ours(it) }?.clone() }
        held[player.uniqueId] = originals
        player.persistentDataContainer.set(keptSlots, PersistentDataType.INTEGER_ARRAY, order.toIntArray())
        player.persistentDataContainer.set(keptItems, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(order.map { originals[it] ?: ItemStack.empty() }))
    }

    /** Puts [stack] in [slot] of the inventory of [player]; null puts back what the slot held. Nothing happens once it is [end]ed. */
    fun put(player: Player, slot: Int, stack: ItemStack?) {
        val originals = held[player.uniqueId] ?: return
        if (slot !in originals) return
        player.inventory.setItem(slot, stack ?: originals[slot])
    }

    /** Gives [player] back what the menu replaced. Does nothing when nothing is lent. */
    fun end(player: Player) {
        val originals = held.remove(player.uniqueId) ?: return
        give(player, originals)
    }

    /**
     * For a player who joins: gives back what a menu replaced when the server went down with it open (the keep is in their
     * data), and takes out any item of a menu that is left in the inventory.
     */
    fun recover(player: Player) {
        val data = player.persistentDataContainer
        val slots = data.get(keptSlots, PersistentDataType.INTEGER_ARRAY)
        val bytes = data.get(keptItems, PersistentDataType.BYTE_ARRAY)
        if (slots != null && bytes != null) {
            val items = ItemStack.deserializeItemsFromBytes(bytes)
            give(player, slots.indices.associate { slots[it] to items.getOrNull(it)?.takeUnless { stack -> stack.isEmpty } })
        }
        val inventory = player.inventory
        for (slot in 0 until inventory.size) if (ours(inventory.getItem(slot))) inventory.setItem(slot, null)
    }

    private fun give(player: Player, originals: Map<Int, ItemStack?>) {
        val inventory = player.inventory
        val plan = settle(originals, { slot -> inventory.getItem(slot)?.takeUnless { it.isEmpty } }, ::ours)
        for (entry in plan.restore) inventory.setItem(entry.key, entry.value)
        for (stack in plan.give) inventory.addItem(stack).values.forEach { player.world.dropItem(player.location, it) }
        player.persistentDataContainer.remove(keptSlots)
        player.persistentDataContainer.remove(keptItems)
    }

    /**
     * A player who dies with a menu open: the drops were collected with the menu's items among them and without what they
     * replaced. The menu's items are taken out, and what they replaced is dropped (or kept, with `keepInventory`).
     */
    fun died(event: PlayerDeathEvent) {
        val player = event.entity
        val originals = held[player.uniqueId] ?: return
        event.drops.removeIf { ours(it) }
        end(player)
        if (!event.keepInventory) originals.values.filterNotNull().forEach { event.drops.add(it) }
    }
}
