package mc.xalitoria.menu.paper.condition

import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.xalitoria.menu.Condition
import net.xalitoria.menu.MenuContext
import mc.xalitoria.menu.paper.api.TypeRegistry

/** The conditions that combine others. They stop at the first one that decides the answer. */
object Logic {
    fun register(registry: TypeRegistry) {
        registry.condition<And>()
        registry.condition<Or>()
        registry.condition<OneOf>()
        registry.condition<AtLeast>()
        registry.condition<Chance>()
    }

    /** Holds when all of [conditions] do. */
    @Serializable @SerialName("and")
    data class And(val conditions: List<Condition>) : Condition {
        override fun test(ctx: MenuContext): Boolean = conditions.all { it.test(ctx) }
    }

    /** Holds when any of [conditions] does. */
    @Serializable @SerialName("or")
    data class Or(val conditions: List<Condition>) : Condition {
        override fun test(ctx: MenuContext): Boolean = conditions.any { it.test(ctx) }
    }

    /** Holds when exactly one of [conditions] does. */
    @Serializable @SerialName("one-of")
    data class OneOf(val conditions: List<Condition>) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            var holding = 0
            for (condition in conditions) if (condition.test(ctx) && ++holding > 1) return false
            return holding == 1
        }
    }

    /** Holds when [count] or more of [conditions] do. */
    @Serializable @SerialName("at-least")
    data class AtLeast(val count: Int, val conditions: List<Condition>) : Condition {
        init {
            require(count >= 0) { "count can't be negative" }
        }

        override fun test(ctx: MenuContext): Boolean {
            if (count == 0) return true
            var holding = 0
            for (condition in conditions) if (condition.test(ctx) && ++holding >= count) return true
            return false
        }
    }

    /**
     * Holds [percent] times in a hundred (0 to 100). It is asked again on every redraw of an item, so it belongs in the
     * `require` of a click, not in the `view` of an item.
     */
    @Serializable @SerialName("chance")
    data class Chance(val percent: Double) : Condition {
        init {
            require(percent in 0.0..100.0) { "percent is between 0 and 100" }
        }

        override fun test(ctx: MenuContext): Boolean = Random.nextDouble() * 100 < percent
    }
}
