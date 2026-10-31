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
    /** The base potion of a potion, tipped arrow or the like, such as `strong_healing`. */
    val potionType: String? = null,
    /** Enchantment key to level, kept in an enchanted book rather than put on the item. */
    val storedEnchants: Map<String, Int> = emptyMap(),
    /** How worn out the item is, in points of durability; the bar shows once it is more than 0. */
    val damage: Int? = null,
    /** The base name, which stays plain (not italic) and which renaming in an anvil replaces; [name] is the renamed one. */
    val itemName: String? = null,
    val rarity: Rarity? = null,
    /** Whether the item shimmers like an enchanted one: true forces it, false takes it away from an enchanted item. */
    val glow: Boolean? = null,
    /** How many fit in a stack, 1 to 99. */
    val maxStackSize: Int? = null,
    /** Show no tooltip at all, not even the name. */
    val hideTooltip: Boolean = false,
    /** The key of the model resource packs give the item, such as `mypack:crown`. */
    val itemModel: String? = null,
    val food: Food? = null,
    val trim: Trim? = null,
    val book: Book? = null,
    val firework: Firework? = null,
    val fish: Fish? = null,
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

enum class Rarity { COMMON, UNCOMMON, RARE, EPIC }

/** An item that can be eaten, whatever it is made of. [saturation] is what is added to the player's, not a multiple. */
@Serializable
data class Food(val nutrition: Int, val saturation: Float = 0f, val alwaysEdible: Boolean = false)

/** An armor trim: [pattern] and [material] are keys such as `coast` and `gold`. */
@Serializable
data class Trim(val pattern: String, val material: String)

/** The text of a written book. A page is text like any other, with colors and placeholders. */
@Serializable
data class Book(val pages: List<String>, val title: String = "Book", val author: String = "")

/** Firework rocket or star settings. A rocket flies for [power] (1 to 3) and bursts with every effect; a star shows the first. */
@Serializable
data class Firework(val effects: List<FireworkEffect> = [], val power: Int = 1)

/** One burst. [colors] and [fade] are `#rrggbb`. */
@Serializable
data class FireworkEffect(
    val type: FireworkType = FireworkType.BALL,
    val colors: List<String>,
    val fade: List<String> = [],
    val flicker: Boolean = false,
    val trail: Boolean = false,
)

enum class FireworkType { BALL, BALL_LARGE, STAR, BURST, CREEPER }

/** A bucket of tropical fish: the look of the fish in it. Patterns are the names the game uses, such as `KOB` or `BRINELY`. */
@Serializable
data class Fish(val pattern: String, val bodyColor: String = "WHITE", val patternColor: String = "WHITE")

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
