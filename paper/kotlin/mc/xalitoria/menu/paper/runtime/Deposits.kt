package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.action.Inventory
import net.xalitoria.menu.model.Accept
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an item that is offered to a slot of a menu is, as the server has it. Both ways to offer one are read from the
 * server's own state when they are asked, never from what the click said, and nothing is removed until [take].
 */
interface Offer {
    /** A copy of what is there now, or null if there is nothing. */
    fun stack(): ItemStack?

    /** Removes [amount] of what is there. @return whether there were that many */
    fun take(amount: Int): Boolean

    /** Puts [stack], which [take] removed, back: where it was if there is room, else into the inventory, else on the ground. */
    fun restore(stack: ItemStack)
}

/** Gives [stack] back to [player]: what does not fit in their inventory drops at their feet, so that nothing taken is lost. */
private fun giveBack(player: Player, stack: ItemStack) {
    for (left in player.inventory.addItem(stack).values) player.world.dropItemNaturally(player.location, left)
}

/** The item on the player's cursor. */
class CursorOffer(private val player: Player) : Offer {
    override fun stack(): ItemStack? = player.itemOnCursor.takeUnless { it.isEmpty }?.clone()

    override fun take(amount: Int): Boolean {
        val held = player.itemOnCursor
        if (held.isEmpty || held.amount < amount) return false
        if (held.amount == amount) player.setItemOnCursor(null) else player.setItemOnCursor(held.clone().also { it.amount = held.amount - amount })
        return true
    }

    override fun restore(stack: ItemStack) {
        val held = player.itemOnCursor
        if (held.isEmpty) player.setItemOnCursor(stack)
        else if (held.isSimilar(stack) && held.amount + stack.amount <= held.maxStackSize) player.setItemOnCursor(held.clone().also { it.amount = held.amount + stack.amount })
        else giveBack(player, stack)
    }
}

/** The stack in a slot of the player's own inventory. */
class SlotOffer(private val player: Player, private val slot: Int) : Offer {
    override fun stack(): ItemStack? = player.inventory.getItem(slot)?.takeUnless { it.isEmpty }?.clone()

    override fun take(amount: Int): Boolean {
        val held = player.inventory.getItem(slot)
        if (held == null || held.isEmpty || held.amount < amount) return false
        player.inventory.setItem(slot, if (held.amount == amount) null else held.clone().also { it.amount = held.amount - amount })
        return true
    }

    override fun restore(stack: ItemStack) {
        val held = player.inventory.getItem(slot)
        if (held == null || held.isEmpty) player.inventory.setItem(slot, stack)
        else if (held.isSimilar(stack) && held.amount + stack.amount <= held.maxStackSize) player.inventory.setItem(slot, held.clone().also { it.amount = held.amount + stack.amount })
        else giveBack(player, stack)
    }
}

/**
 * Takes the item before [run] and gives it back if [run] does not end well, so that the item is never both kept by the
 * player and paid for. The other order lets whatever [run] does to the window (a closed inventory returns the cursor's item
 * to the inventory) or to the item (another plugin, a command) leave the reward given with the item still there.
 * @return whether [run] ended well, with the item gone; false with the item back (or never taken) otherwise
 */
internal inline fun <T : Any> reserving(take: () -> T?, restore: (T) -> Unit, run: () -> Boolean): Boolean {
    val taken = take() ?: return false
    val ok = try {
        run()
    } catch (t: Throwable) {
        restore(taken)
        throw t
    }
    if (!ok) restore(taken)
    return ok
}

/** The slots of a menu that take an item from the player: the `accept` of an item. */
class Deposits(private val plugin: XaliMenu) {
    /** Whether [offer] holds something [accept] takes: an item that matches, in the amount it asks for. */
    fun fits(accept: Accept, offer: Offer, ctx: Context): Boolean {
        val stack = offer.stack() ?: return false
        return stack.amount >= accept.amount && Inventory.Filter(accept.material, accept.name, accept.model).matches(stack, ctx)
    }

    private val settling = Deferred()

    /**
     * Whether the viewer is in the middle of a deposit, in which case [task] is kept and runs once the item has been taken (or
     * has not). Opening a menu is what needs it: the window that was open gives the cursor's item back to the inventory, and
     * the item would no longer be where it was offered.
     * @return false when there is no deposit going on, and [task] has not been kept
     */
    fun afterSettling(player: Player, task: () -> Unit): Boolean = settling.defer(player.uniqueId, task)

    /**
     * Offers [offer] to [accept]. An item that does not fit runs `reject`. One that does runs `then`, and only when that
     * ran to its end, with nothing aborting or failing, does it stay taken: the item is taken first and given back otherwise
     * (see [reserving]), so that what `then` does to the window cannot leave the reward given and the item kept. A menu
     * that `then` opens is opened after that, whether the item was taken or not. Run on the player's region thread, where nothing else changes the item meanwhile.
     * @return whether the item was taken
     */
    fun offer(accept: Accept, offer: Offer, ctx: Context): Boolean {
        if (!fits(accept, offer, ctx)) {
            plugin.actions.run(accept.reject, ctx)
            return false
        }
        val stack = offer.stack() ?: return false
        val id = ctx.player.uniqueId
        settling.begin(id)
        try {
            val given = ctx.with(mapOf("item" to stack.type.key.key, "amount" to accept.amount.toString()))
            val reserved = stack.clone().also { it.amount = accept.amount }
            return reserving(
                take = { reserved.takeIf { offer.take(accept.amount) } },
                restore = { offer.restore(it) },
                run = { plugin.actions.attempt(accept.then, given) },
            )
        } finally {
            // Each on its own, so that one that fails neither changes what the offer came to nor keeps the others from running.
            for (task in settling.end(id)) {
                runCatching(task).onFailure { plugin.logger.warning("A menu opened by '${ctx.menu}' after its item was taken failed: ${it.message}") }
            }
        }
    }
}

/**
 * What waits for a deposit to be settled, by player: the tasks that were put off until then. A deposit that starts inside
 * another one (the actions of one run another) is part of it, and what it puts off waits for the outermost to end.
 */
class Deferred {
    private class Deposit(var depth: Int = 0, val tasks: MutableList<() -> Unit> = mutableListOf())

    private val going = ConcurrentHashMap<UUID, Deposit>()

    /** Starts a deposit for [id]. */
    fun begin(id: UUID) {
        going.computeIfAbsent(id) { Deposit() }.depth++
    }

    /** Keeps [task] for the end of [id]'s deposit. @return false if there is none going on */
    fun defer(id: UUID, task: () -> Unit): Boolean {
        val deposit = going[id] ?: return false
        deposit.tasks += task
        return true
    }

    /** Ends [id]'s deposit, the innermost first. @return what was kept, in order, once the outermost has ended; nothing before */
    fun end(id: UUID): List<() -> Unit> {
        val deposit = going[id] ?: return emptyList()
        if (--deposit.depth > 0) return emptyList()
        going.remove(id)
        return deposit.tasks
    }
}
