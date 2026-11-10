package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.action.Inventory
import net.xalitoria.menu.model.Accept
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Where an item that is offered to a slot of a menu is, as the server has it. Both ways to offer one are read from the
 * server's own state when they are asked, never from what the click said, and nothing is removed until [take].
 */
interface Offer {
    /** A copy of what is there now, or null if there is nothing. */
    fun stack(): ItemStack?

    /** Removes [amount] of what is there. @return whether there were that many */
    fun take(amount: Int): Boolean
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
}

/** The slots of a menu that take an item from the player: the `accept` of an item. */
class Deposits(private val plugin: XaliMenu) {
    /** Whether [offer] holds something [accept] takes: an item that matches, in the amount it asks for. */
    fun fits(accept: Accept, offer: Offer, ctx: Context): Boolean {
        val stack = offer.stack() ?: return false
        return stack.amount >= accept.amount && Inventory.Filter(accept.material, accept.name, accept.model).matches(stack, ctx)
    }

    /**
     * Offers [offer] to [accept]. An item that does not fit runs `reject`. One that does runs `then`, and only when that
     * ran to its end, with nothing aborting or failing, is it taken, after looking at the server's state once more: what
     * then ran may have moved it. Run on the player's region thread, where nothing else changes the item meanwhile.
     * @return whether the item was taken
     */
    fun offer(accept: Accept, offer: Offer, ctx: Context): Boolean {
        if (!fits(accept, offer, ctx)) {
            plugin.actions.run(accept.reject, ctx)
            return false
        }
        val stack = offer.stack() ?: return false
        val given = ctx.with(mapOf("item" to stack.type.key.key, "amount" to accept.amount.toString()))
        if (!plugin.actions.attempt(accept.then, given)) return false
        if (!fits(accept, offer, ctx) || !offer.take(accept.amount)) {
            plugin.logger.warning("${ctx.player.name}'s ${stack.type.key.key} was gone when '${ctx.menu}' came to take it, after its actions had run")
            return false
        }
        return true
    }
}
