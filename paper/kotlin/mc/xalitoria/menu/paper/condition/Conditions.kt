package mc.xalitoria.menu.paper.condition

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.runtime.ItemFactory
import mc.xalitoria.util.message.PLAIN
import net.kyori.adventure.text.Component

/** The conditions XaliMenu comes with. Text may hold placeholders: PlaceholderAPI's, and `{name}` for the menu's arguments. */
object Conditions {
    fun register(registry: TypeRegistry) {
        registry.condition<Permission>()
        registry.condition<Money>()
        registry.condition<Item>()
        registry.condition<Compare>()
        registry.condition<Text>()
        registry.condition<Regex>()
        registry.condition<Not>()
        registry.condition<Never>()
    }

    private fun number(ctx: MenuContext, text: String): BigDecimal? = ctx.parse(text).trim().toBigDecimalOrNull()

    private fun plain(component: Component?): String = component?.let(PLAIN::serialize).orEmpty()

    @Serializable @SerialName("permission")
    data class Permission(val node: String) : Condition {
        override fun test(ctx: MenuContext): Boolean = ctx.player.hasPermission(ctx.parse(node))
    }

    /** The viewer has at least [amount] (a number or a placeholder) of [currency], the default one without it. */
    @Serializable @SerialName("money")
    data class Money(val amount: String, val currency: String? = null) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            val value = number(ctx, amount) ?: return false
            val economy = XaliMenu.economy
            if (economy?.available != true) {
                debug("A money condition needs VaultUnlocked and an economy plugin")
                return false
            }
            return economy.has(ctx.player, value, currency)
        }
    }

    /** The viewer carries [amount] or more of [material]; [name] and [lore], when set, must match the item's. */
    @Serializable @SerialName("item")
    data class Item(
        val material: String,
        val amount: Int = 1,
        val name: String? = null,
        val lore: List<String> = [],
    ) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            val type = ItemFactory.material(ctx.parse(material)) ?: return false
            val wantedName = name?.let { plain(ctx.component(it)) }
            val wantedLore = lore.map { plain(ctx.component(it)) }
            var count = 0
            for (stack in ctx.player.inventory.contents) {
                if (stack == null || stack.type != type) continue
                if (wantedName != null && plain(stack.itemMeta?.displayName()) != wantedName) continue
                if (wantedLore.isNotEmpty() && stack.itemMeta?.lore().orEmpty().map(::plain) != wantedLore) continue
                count += stack.amount
            }
            return count >= amount
        }
    }

    /** Compares [left] and [right] as numbers; if either isn't one it never holds. */
    @Serializable @SerialName("compare")
    data class Compare(val left: String, val op: Op, val right: String) : Condition {
        enum class Op { GT, GTE, EQ, NEQ, LT, LTE }

        override fun test(ctx: MenuContext): Boolean {
            val a = number(ctx, left) ?: return false
            val b = number(ctx, right) ?: return false
            val order = a.compareTo(b)
            return when (op) {
                GT -> order > 0
                GTE -> order >= 0
                EQ -> order == 0
                NEQ -> order != 0
                LT -> order < 0
                LTE -> order <= 0
            }
        }
    }

    @Serializable @SerialName("text")
    data class Text(val input: String, val op: Op, val value: String) : Condition {
        enum class Op { EQUALS, EQUALS_IGNORE_CASE, CONTAINS }

        override fun test(ctx: MenuContext): Boolean {
            val a = ctx.parse(input)
            val b = ctx.parse(value)
            return when (op) {
                EQUALS -> a == b
                EQUALS_IGNORE_CASE -> a.equals(b, ignoreCase = true)
                CONTAINS -> b in a
            }
        }
    }

    /** [input] matches the whole of [pattern]; a pattern that isn't valid never matches. */
    @Serializable @SerialName("regex")
    data class Regex(val input: String, val pattern: String) : Condition {
        override fun test(ctx: MenuContext): Boolean = try {
            kotlin.text.Regex(ctx.parse(pattern)).matches(ctx.parse(input))
        } catch (e: IllegalArgumentException) {
            debug("Bad regex '{0}': {1}", pattern, e.message)
            false
        }
    }

    @Serializable @SerialName("not")
    data class Not(val condition: Condition) : Condition {
        override fun test(ctx: MenuContext): Boolean = !condition.test(ctx)
    }

    /**
     * Never holds. The importer writes it for a test XaliMenu can't run (a JavaScript expression, say), so the menu
     * fails closed instead of quietly showing an item or opening for everyone.
     */
    @Serializable @SerialName("never")
    data object Never : Condition {
        override fun test(ctx: MenuContext): Boolean = false
    }
}
