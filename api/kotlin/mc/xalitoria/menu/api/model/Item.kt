package mc.xalitoria.menu.api.model

import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Action

/**
 * An item of a menu.
 *
 * [material] is a Minecraft item key (`diamond_pickaxe`), or a prefixed source: `head:<player>` (a player's head, with
 * their SkinsRestorer skin when it is installed), `texture:<base64>`, `hdb:<id>` (HeadDatabase), `itemsadder:<id>` and
 * `nexo:<id>`. The item sits in [slot] and [slots]; when several items claim a slot, the one with the lowest
 * [priority] whose [view] gate passes is shown.
 */
@Serializable
data class Item(
    val material: String,
    val slot: Int? = null,
    val slots: List<Int> = [],
    val priority: Int = 0,
    val amount: Int = 1,
    /** A placeholder that gives the amount at draw time, taking the place of [amount]. */
    val dynamicAmount: String? = null,
    val name: String? = null,
    val lore: List<String> = [],
    /** Enchantment key to level. */
    val enchants: Map<String, Int> = emptyMap(),
    /** Data component keys (`enchantments`, `attribute_modifiers`, ...) left out of the tooltip. */
    val hide: List<String> = [],
    val unbreakable: Boolean = false,
    val model: Int? = null,
    /** Dye color of leather armor and the like, as `#rrggbb`. */
    val color: String? = null,
    val banner: List<BannerLayer> = [],
    val potion: List<PotionEffect> = [],
    /** Extra NBT as an SNBT compound, such as `{menu:"jobs",tier:3}`. */
    val nbt: String? = null,
    /** Draw this item again on every menu refresh, to keep its placeholders current. */
    val update: Boolean = false,
    val view: Gate? = null,
    val click: Clicks = Clicks(),
)

/** A pattern on a banner. */
@Serializable
data class BannerLayer(val color: String, val pattern: String)

/** [duration] is in ticks. */
@Serializable
data class PotionEffect(val type: String, val duration: Int, val amplifier: Int = 0)

/** What a click on an item does, per kind of click. [any] handles the kinds with no handler of their own. */
@Serializable
data class Clicks(
    val any: Handler? = null,
    val left: Handler? = null,
    val right: Handler? = null,
    val shiftLeft: Handler? = null,
    val shiftRight: Handler? = null,
    val middle: Handler? = null,
)

/** Runs [actions] if the [require] gate, when there is one, passes. */
@Serializable
data class Handler(
    val actions: List<Action> = [],
    val require: Gate? = null,
)
