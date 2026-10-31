package mc.xalitoria.menu.paper.action

import io.papermc.paper.datacomponent.DataComponentTypes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.model.Item
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.ItemFactory
import mc.xalitoria.util.message.PLAIN
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** The actions that put items into the viewer's inventory and take them out. */
object Inventory {
    fun register(registry: TypeRegistry) {
        registry.action<GiveItem>()
        registry.action<TakeItem>()
        registry.action<ClearItems>()
    }

    /**
     * Which items of the inventory an action means: the ones of [material] (any when absent), with the name [name] as it
     * reads without colors, and the custom model [model]. A material that is no item matches nothing.
     */
    class Filter(private val material: String?, private val name: String?, private val model: Int?) {
        fun matches(stack: ItemStack?, ctx: MenuContext): Boolean {
            if (stack == null || stack.isEmpty) return false
            if (material != null) {
                val wanted = ItemFactory.material(ctx.parse(material)) ?: return false
                if (stack.type != wanted) return false
            }
            if (name != null && stack.itemMeta?.displayName()?.let(PLAIN::serialize) != PLAIN.serialize(ctx.component(name))) return false
            if (model != null && stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats()?.firstOrNull()?.toInt() != model) return false
            return true
        }
    }

    /** What to do with the part of the items that doesn't fit into the inventory. */
    enum class Overflow {
        /** Drop it at the viewer's feet. */
        DROP,

        /** Give nothing, and stop the actions after this one. */
        ABORT,
    }

    /** Gives the viewer [item] (the same settings as an item of a menu, without its slot and clicks). */
    @Serializable @SerialName("give-item")
    data class GiveItem(val item: Item, val overflow: Overflow = Overflow.DROP) : Action {
        override fun run(ctx: MenuContext) {
            val player = ctx.player
            val stack = XaliMenu.INSTANCE.items.build(item, ctx as Context)
            if (overflow == Overflow.ABORT && !fits(player, stack)) {
                debug("{0} has no room for {1}", player.name, stack.type)
                ctx.abort()
            }
            player.inventory.addItem(stack).values.forEach { player.world.dropItem(player.location, it) }
        }

        /** Whether [stack] fits whole, tried on a copy of the inventory so the real one is left alone. */
        private fun fits(player: Player, stack: ItemStack): Boolean {
            val copy = Bukkit.createInventory(null, 36)
            copy.contents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
            return copy.addItem(stack.clone()).isEmpty()
        }
    }

    /**
     * Takes [amount] of the items that match from the viewer. If they have fewer nothing is taken and the actions after
     * this one don't run, so it can stand before what is given for them.
     */
    @Serializable @SerialName("take-item")
    data class TakeItem(
        val material: String? = null,
        val amount: Int = 1,
        val name: String? = null,
        val model: Int? = null,
    ) : Action {
        init {
            require(amount >= 1) { "take-item takes at least one" }
        }

        override fun run(ctx: MenuContext) {
            val contents = ctx.player.inventory.storageContents
            val filter = Filter(material, name, model)
            val found = contents.indices.filter { filter.matches(contents[it], ctx) }
            val removals = removals(found.map { contents[it]!!.amount }, amount)
            if (removals == null) {
                debug("{0} has fewer than {1} of what is to be taken", ctx.player.name, amount)
                ctx.abort()
            }
            for ([i, slot] in found.withIndex()) {
                val stack = contents[slot]!!
                if (stack.amount <= removals[i]) contents[slot] = null else stack.amount -= removals[i]
            }
            ctx.player.inventory.storageContents = contents
        }
    }

    /** Takes every item that matches from the viewer: with nothing to match on, the whole inventory. */
    @Serializable @SerialName("clear-items")
    data class ClearItems(val material: String? = null, val name: String? = null, val model: Int? = null) : Action {
        override fun run(ctx: MenuContext) {
            val inventory = ctx.player.inventory
            if (material == null && name == null && model == null) return inventory.clear()
            val filter = Filter(material, name, model)
            val contents = inventory.storageContents
            for (slot in contents.indices) if (filter.matches(contents[slot], ctx)) contents[slot] = null
            inventory.storageContents = contents
        }
    }

    /**
     * How much to take from each stack, in order, to take [wanted] in all; null if the stacks of [amounts] hold less.
     */
    fun removals(amounts: List<Int>, wanted: Int): List<Int>? {
        if (amounts.sum() < wanted) return null
        var left = wanted
        return amounts.map { have -> minOf(have, left).also { left -= it } }
    }
}
