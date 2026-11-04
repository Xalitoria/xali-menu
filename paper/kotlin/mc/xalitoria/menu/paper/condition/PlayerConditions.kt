package mc.xalitoria.menu.paper.condition

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.Scope
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.action.Inventory
import mc.xalitoria.menu.paper.api.TypeRegistry
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/** The conditions about the viewer's body, belongings and surroundings. */
object PlayerConditions {
    fun register(registry: TypeRegistry) {
        registry.condition<FreeSlots>()
        registry.condition<HeldItem>()
        registry.condition<Gamemode>()
        registry.condition<Health>()
        registry.condition<Food>()
        registry.condition<Level>()
        registry.condition<Experience>()
        registry.condition<World>()
        registry.condition<Region>()
        registry.condition<Near>()
        registry.condition<Online>()
        registry.condition<OnlineCount>()
        registry.condition<Group>()
        registry.condition<VariableExists>()
        registry.condition<Cooldown>()
    }

    /**
     * Whether [value] is at least [min] and at most [max], each a number or a placeholder; one that is left out does not
     * limit. A limit that is not a number never lets anything through.
     */
    fun within(ctx: MenuContext, value: Double, min: String?, max: String?): Boolean {
        fun limit(text: String?): BigDecimal? = text?.let { ctx.parse(it).trim().toBigDecimalOrNull() }
        val low = limit(min)
        val high = limit(max)
        if (min != null && low == null || max != null && high == null) return false
        val number = BigDecimal.valueOf(value)
        return (low == null || number >= low) && (high == null || number <= high)
    }

    /** How many of [slots] hold nothing. */
    fun empty(slots: Array<ItemStack?>): Int = slots.count { it == null || it.isEmpty }

    /** The viewer has [min] or more empty slots in their inventory (not counting armor and the off hand). */
    @Serializable @SerialName("free-slots")
    data class FreeSlots(val min: Int = 1) : Condition {
        init {
            require(min in 0..36) { "min is between 0 and 36" }
        }

        override fun test(ctx: MenuContext): Boolean = empty(ctx.player.inventory.storageContents) >= min
    }

    /** The viewer holds at least [amount] of an item that matches, in [hand]. */
    @Serializable @SerialName("held-item")
    data class HeldItem(
        val material: String? = null,
        val name: String? = null,
        val model: Int? = null,
        val amount: Int = 1,
        val hand: EquipmentSlot = EquipmentSlot.HAND,
    ) : Condition {
        init {
            require(amount >= 1) { "amount is at least 1" }
            require(hand == EquipmentSlot.HAND || hand == EquipmentSlot.OFF_HAND) { "hand is HAND or OFF_HAND" }
        }

        override fun test(ctx: MenuContext): Boolean {
            val stack = ctx.player.inventory.getItem(hand)
            return Inventory.Filter(material, name, model).matches(stack, ctx) && stack.amount >= amount
        }
    }

    /** The viewer is in one of the game modes [modes]. */
    @Serializable @SerialName("gamemode")
    data class Gamemode(val modes: List<GameMode>) : Condition {
        init {
            require(modes.isNotEmpty()) { "gamemode needs a mode" }
        }

        override fun test(ctx: MenuContext): Boolean = ctx.player.gameMode in modes
    }

    /** The viewer's health is between [min] and [max], each a number or a placeholder. */
    @Serializable @SerialName("health")
    data class Health(val min: String? = null, val max: String? = null) : Condition {
        override fun test(ctx: MenuContext): Boolean = within(ctx, ctx.player.health, min, max)
    }

    /** The viewer's food level (0 to 20) is between [min] and [max]. */
    @Serializable @SerialName("food")
    data class Food(val min: String? = null, val max: String? = null) : Condition {
        override fun test(ctx: MenuContext): Boolean = within(ctx, ctx.player.foodLevel.toDouble(), min, max)
    }

    /** The viewer's experience level is between [min] and [max]. */
    @Serializable @SerialName("level")
    data class Level(val min: String? = null, val max: String? = null) : Condition {
        override fun test(ctx: MenuContext): Boolean = within(ctx, ctx.player.level.toDouble(), min, max)
    }

    /** The viewer's experience points in all (as `experience` charges them) are between [min] and [max]. */
    @Serializable @SerialName("experience")
    data class Experience(val min: String? = null, val max: String? = null) : Condition {
        override fun test(ctx: MenuContext): Boolean = within(ctx, ctx.player.calculateTotalExperiencePoints().toDouble(), min, max)
    }

    /** The viewer is less than [distance] blocks from the point [x], [y], [z] of the world [world]. */
    @Serializable @SerialName("near")
    data class Near(val world: String, val x: Double, val y: Double, val z: Double, val distance: Double) : Condition {
        init {
            require(distance > 0) { "near needs a distance above 0" }
        }

        override fun test(ctx: MenuContext): Boolean {
            val at = ctx.player.location
            if (!at.world.name.equals(world, ignoreCase = true)) return false
            val dx = at.x - x
            val dy = at.y - y
            val dz = at.z - z
            return dx * dx + dy * dy + dz * dz < distance * distance
        }
    }

    /** The viewer is in one of the worlds [names] (placeholders allowed). */
    @Serializable @SerialName("world")
    data class World(val names: List<String>) : Condition {
        init {
            require(names.isNotEmpty()) { "world needs a name" }
        }

        override fun test(ctx: MenuContext): Boolean = names.any { ctx.parse(it).trim().equals(ctx.player.world.name, ignoreCase = true) }
    }

    /**
     * The viewer stands in one of the WorldGuard regions [names] (placeholders allowed), or in any region when there are none.
     * Without WorldGuard it never holds.
     */
    @Serializable @SerialName("region")
    data class Region(val names: List<String> = []) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            val here = XaliMenu.INSTANCE.regions?.at(ctx.player.location) ?: return false
            return if (names.isEmpty()) here.isNotEmpty() else names.any { wanted -> here.any { it.equals(ctx.parse(wanted).trim(), ignoreCase = true) } }
        }
    }

    /** The player called [player] (a name; may be a placeholder) is online. */
    @Serializable @SerialName("online")
    data class Online(val player: String) : Condition {
        override fun test(ctx: MenuContext): Boolean = ctx.player.server.getPlayerExact(ctx.parse(player).trim()) != null
    }

    /** The number of players online is between [min] and [max]. */
    @Serializable @SerialName("online-count")
    data class OnlineCount(val min: String? = null, val max: String? = null) : Condition {
        override fun test(ctx: MenuContext): Boolean = within(ctx, ctx.player.server.onlinePlayers.size.toDouble(), min, max)
    }

    /** The viewer is in the permission group [name], as the permission plugin behind Vault knows it. */
    @Serializable @SerialName("group")
    data class Group(val name: String) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            val economy = XaliMenu.economy
            if (economy?.hasPermissionProvider != true) {
                debug("A group condition needs Vault and a permission plugin")
                return false
            }
            return economy.inGroup(ctx.player, ctx.parse(name))
        }
    }

    /** Where [scope] puts a variable of the viewer. */
    private fun owner(ctx: MenuContext, scope: VariableExists.Scope): Scope =
        if (scope == VariableExists.Scope.GLOBAL) Scope.Global else Scope.Player(ctx.player.uniqueId)

    /** A variable called [name] exists (and has not expired), a global one or, with [scope] `PLAYER`, the viewer's own. */
    @Serializable @SerialName("variable-exists")
    data class VariableExists(val name: String, val scope: Scope = Scope.GLOBAL) : Condition {
        enum class Scope { GLOBAL, PLAYER }

        override fun test(ctx: MenuContext): Boolean = XaliMenu.INSTANCE.variables[owner(ctx, scope), ctx.parse(name)] != null
    }

    /**
     * Holds when the cooldown [key] of the viewer is not running (or, with [scope] `GLOBAL`, the one everybody shares).
     * The cooldown is started by the `cooldown` action, which keeps it as the variable `cooldown_<key>` with an expiry; a
     * store that has not loaded yet counts as running, so waiting for it cannot be used to get around one.
     */
    @Serializable @SerialName("cooldown")
    data class Cooldown(val key: String, val scope: VariableExists.Scope = VariableExists.Scope.PLAYER) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            val name = variable(ctx.parse(key))
            val variables = XaliMenu.INSTANCE.variables
            return variables.isAvailable(name) && variables[owner(ctx, scope), name] == null
        }
    }

    /** The name of the variable that holds the cooldown [key]. */
    fun variable(key: String): String = "cooldown_" + key.trim().replace(Regex("[^A-Za-z0-9_]"), "_")
}
