package net.xalitoria.menu.model

import kotlinx.serialization.Serializable
import net.xalitoria.menu.Action

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
    /** The item key or prefixed source. May contain placeholders. */
    val material: String,
    /** The slot the item occupies. Slots count from 0 at the top left. */
    @Within(min = 0)
    val slot: Int? = null,
    /** More slots the item occupies, in addition to [slot]. */
    @Within(min = 0)
    val slots: List<Int> = [],
    /**
     * Slots of the player's own inventory this item is drawn in as well, as the game numbers them: 0 to 8 the hotbar, 9 to 35
     * the three rows above it. It is the same item, so it is defined once and used in the menu and in the rows under it. The
     * item takes the place of what the player has there, which comes back when the menu closes; nothing can be moved in or out.
     */
    @Within(0, 35)
    val inventorySlots: List<Int> = [],
    /** Decides between items that claim the same slot: the lowest value is tried first. */
    val priority: Int = 0,
    /** The number of items in the stack, 1 to 99: a number, or text with placeholders that works out to one when the item is drawn. */
    @Within(1, 99)
    val amount: DynamicInt = DynamicInt(1),
    /** The display name. Colors, placeholders and `{name}` arguments are allowed. */
    val name: String? = null,
    /** The lines under the name, with the same formatting as [name]. */
    val lore: List<String> = [],
    /** Enchantment key to level. */
    val enchants: Map<String, Int> = emptyMap(),
    /** Data component keys (`enchantments`, `attribute_modifiers`, ...) left out of the tooltip. */
    val hide: List<String> = [],
    /** Whether the item takes no durability damage. A switch, or text with placeholders that works out to one. */
    val unbreakable: DynamicBoolean = DynamicBoolean(false),
    /** The custom model data number that resource packs use to choose a model. A number, or text with placeholders. */
    @Within(min = 0)
    val model: DynamicInt? = null,
    /** Dye color of leather armor and the like, as `#rrggbb`. */
    val color: String? = null,
    /** The patterns of a banner, from the bottom layer to the top. */
    val banner: List<BannerLayer> = [],
    /** The dye color of a shield's base, such as `red`; the patterns of a shield are the `banner` ones. */
    val baseColor: String? = null,
    /** Custom effects of a potion, splash potion, lingering potion or tipped arrow. */
    val potion: List<PotionEffect> = [],
    /** The base potion of a potion, tipped arrow or the like, such as `strong_healing`. */
    val potionType: String? = null,
    /** Enchantment key to level, kept in an enchanted book rather than put on the item. */
    val storedEnchants: Map<String, Int> = emptyMap(),
    /** How worn out the item is, in points of durability; the bar shows once it is more than 0. A number, or text with placeholders. */
    @Within(min = 0)
    val damage: DynamicInt? = null,
    /** The base name, which stays plain (not italic) and which renaming in an anvil replaces; [name] is the renamed one. */
    val itemName: String? = null,
    /** The rarity, which sets the color of the name. */
    val rarity: Rarity? = null,
    /** Whether the item shimmers like an enchanted one: true forces it, false takes it away from an enchanted item. A switch, or text with placeholders. */
    val glow: DynamicBoolean? = null,
    /** How many fit in a stack, 1 to 99. A number, or text with placeholders. */
    @Within(1, 99)
    val maxStackSize: DynamicInt? = null,
    /** Show no tooltip at all, not even the name. A switch, or text with placeholders. */
    val hideTooltip: DynamicBoolean = DynamicBoolean(false),
    /** The key of the model resource packs give the item, such as `mypack:crown`. */
    val itemModel: String? = null,
    /** Makes the item edible. */
    val food: Food? = null,
    /** The trim of a piece of armor. */
    val trim: Trim? = null,
    /** The text of a written book. */
    val book: Book? = null,
    /** The effects of a firework rocket or firework star. */
    val firework: Firework? = null,
    /** The look of the fish in a bucket of tropical fish. */
    val fish: Fish? = null,
    /** Attribute modifiers the item gives while it is held or worn; they replace the ones the material has of its own. */
    val attributes: List<AttributeModifier> = [],
    /** The key of the tooltip background a resource pack gives the item, such as `mypack:fancy`. */
    val tooltipStyle: String? = null,
    /** Makes the item wearable in a slot. */
    val equippable: Equippable? = null,
    /** Makes the item mine blocks. */
    val tool: Tool? = null,
    /** Extra NBT as an SNBT compound, such as `{menu:"jobs",tier:3}`. */
    val nbt: String? = null,
    /** Draw this item again on every menu refresh, to keep its placeholders current. */
    val update: Boolean = false,
    /** Must hold for the item to be shown. An item that fails it leaves the slot to the next item that claims it. */
    val view: Gate? = null,
    /** What a click on the item does. */
    val click: Clicks = Clicks(),
    /**
     * Makes the slot take an item from the player: clicking it with the item on the cursor, or shift-clicking the item in
     * the player's inventory. With `material = air` the slot shows nothing. It doesn't work in a menu that draws in the
     * player's inventory.
     */
    val accept: Accept? = null,
    /** Optional display and click-label overrides for Bedrock Edition players. */
    val bedrock: BedrockItem? = null,
) {
    /** Every slot of the menu the item claims by what it says itself: [slot] and [slots], without repeats. */
    fun claimed(): List<Int> = (listOfNotNull(slot) + slots).distinct()
}

/**
 * What an item that [Item.accept]s takes. The offered item is the one the server has (on the player's cursor, or in the
 * slot they shift-clicked), never what the client says. It is taken only once [then] has run to its end: an action that
 * aborts or fails leaves it where it was, so put what can refuse (a check, a payment) first. In [then] `{item}` is the
 * material of what was offered and `{amount}` how many are taken.
 */
@Serializable
data class Accept(
    /** Runs, in order, for an item that fits. */
    val then: List<Action>,
    /** Which materials fit; any item when absent. May contain placeholders. */
    val material: String? = null,
    /** The name an item must have, as it reads without colors. */
    val name: String? = null,
    /** The custom model data an item must have. */
    val model: Int? = null,
    /** How many are taken from the stack, which must hold at least that many. */
    @Within(1, 99, strict = true)
    val amount: Int = 1,
    /** Runs for an item that does not fit. */
    val reject: List<Action> = [],
) {
    init {
        require(then.isNotEmpty()) { "accept needs actions to run with the item" }
        require(amount in 1..99) { "accept takes from 1 to 99 items" }
    }
}

/** A pattern on a banner. */
@Serializable
data class BannerLayer(
    /** A dye color, such as `red`. */
    val color: String,
    /** A banner pattern key, such as `stripe_top`. */
    val pattern: String,
)

/** The colors an item name can take from its rarity: white, yellow, aqua and light purple. */
enum class Rarity { COMMON, UNCOMMON, RARE, EPIC }

/** An item that can be eaten, whatever it is made of. [saturation] is what is added to the player's, not a multiple. */
@Serializable
data class Food(
    /** The food points restored, in half drumsticks. */
    @Within(min = 0)
    val nutrition: Int,
    val saturation: Float = 0f,
    /** Whether the item can be eaten with a full food bar. */
    val alwaysEdible: Boolean = false,
)

/** An armor trim: [pattern] and [material] are keys such as `coast` and `gold`. */
@Serializable
data class Trim(
    val pattern: String,
    val material: String,
)

/** The text of a written book. A page is text like any other, with colors and placeholders. */
@Serializable
data class Book(
    val pages: List<String>,
    val title: String = "Book",
    val author: String = "",
)

/** Firework rocket or star settings. A rocket flies for [power] (1 to 3) and bursts with every effect; a star shows the first. */
@Serializable
data class Firework(
    /** The bursts. */
    val effects: List<FireworkEffect> = [],
    /** The flight duration of a rocket, 1 to 3. */
    @Within(0, 127)
    val power: Int = 1,
)

/**
 * A change to an attribute, such as `attack_damage` or `max_health`. [slot] is where the item must be for it to count: `any`,
 * `mainhand`, `offhand`, `hand`, `head`, `chest`, `legs`, `feet`, `armor` or `body`.
 */
@Serializable
data class AttributeModifier(
    /** The attribute key, such as `attack_damage`. */
    val attribute: String,
    val amount: Double,
    val operation: AttributeOperation = AttributeOperation.ADD,
    val slot: String = "any",
)

/** [ADD] adds [AttributeModifier.amount]; [MULTIPLY_BASE] adds that fraction of the base; [MULTIPLY_TOTAL] multiplies the result. */
enum class AttributeOperation { ADD, MULTIPLY_BASE, MULTIPLY_TOTAL }

/**
 * Makes an item wearable in [slot] (`HEAD`, `CHEST`, `LEGS`, `FEET`, `BODY`, `OFF_HAND`...). [model] is the key of the
 * equipment model drawn on the wearer, [cameraOverlay] the key of a texture drawn over the view of the wearer.
 */
@Serializable
data class Equippable(
    val slot: String,
    val model: String? = null,
    val cameraOverlay: String? = null,
    /** Whether a right click swaps the item with what the player wears in the slot. */
    val swappable: Boolean = true,
    /** Whether a dispenser can put the item on a player. */
    val dispensable: Boolean = true,
    /** Whether the item loses durability when the wearer is hurt. */
    val damageOnHurt: Boolean = true,
)

/**
 * Makes an item mine blocks, whatever it is made of. [miningSpeed] is the speed for blocks no rule covers (1 is the speed of
 * a hand) and [damagePerBlock] the durability it loses for each block mined.
 */
@Serializable
data class Tool(
    val miningSpeed: Float = 1f,
    @Within(min = 0)
    val damagePerBlock: Int = 1,
    /** Whether a player in creative mode can break blocks with the item. */
    val canDestroyInCreative: Boolean = true,
    val rules: List<ToolRule> = [],
)

/**
 * One rule of a [Tool]: for the [blocks] (block keys such as `stone`, or tags such as `#minecraft:mineable/pickaxe`) the
 * mining speed is [speed] if there is one, and [drops] says whether they drop their items when mined with it.
 */
@Serializable
data class ToolRule(
    val blocks: List<String>,
    val speed: Float? = null,
    val drops: Boolean? = null,
)

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
data class Fish(
    val pattern: String,
    /** A dye color name, such as `RED`. */
    val bodyColor: String = "WHITE",
    /** A dye color name, such as `BLUE`. */
    val patternColor: String = "WHITE",
)

/** One effect of a potion. */
@Serializable
data class PotionEffect(
    /** The effect key, such as `speed`. */
    val type: String,
    /** The length in ticks. 20 ticks are one second. */
    @Within(min = 0)
    val duration: Int,
    /** The level minus one: 0 is level I. */
    @Within(0, 255)
    val amplifier: Int = 0,
)

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
    /** Run in order. */
    val actions: List<Action> = [],
    /** Must hold for the actions to run. */
    val require: Gate? = null,
)
