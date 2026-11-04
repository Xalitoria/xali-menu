package mc.xalitoria.menu.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something a player does that opens a menu, listed in a menu's `open-with`: `{ type = "item", material = "compass",
 * click = "RIGHT" }`. The first activator (of all the menus, by menu name) that matches and whose [require] gate holds
 * opens its menu; the others are not looked at.
 *
 * A menu is not opened again for a player who has it open already, so an activator that fires continuously (stepping on
 * a pressure plate) does not flicker it.
 */
@Serializable
sealed interface Activator {
    /** Must hold for the menu to open; its `deny` actions run when it does not. */
    val require: Gate?

    /**
     * Whether the event that matched is canceled when the menu opens, so that a lever is not pulled and a message is not
     * said. The chat is the exception: it is canceled as soon as the message matches, since the gate is asked later, on
     * the player's own thread. It has no meaning for [Join].
     */
    val cancel: Boolean

    /** The words the menu gets for its `args`. They may use the activator's values, such as `{message}`. */
    val args: List<String>

    /** The click of a player, with an item in the hand (any hand: the main one is used). */
    @Serializable @SerialName("item")
    data class HeldItem(
        /** The item key of the item in hand, such as `compass`; any item when absent. */
        val material: String? = null,
        /** The name of the item in hand, written as in a menu (colors are ignored when comparing). */
        val name: String? = null,
        val model: Int? = null,
        val click: Click = Click.ANY,
        /** Whether the player must be sneaking (`true`) or not (`false`); either when absent. */
        val sneaking: Boolean? = null,
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator

    /** The player joins the server, after [delay] ticks. */
    @Serializable @SerialName("join")
    data class Join(
        val delay: Int = 20,
        override val require: Gate? = null,
        override val cancel: Boolean = false,
        override val args: List<String> = [],
    ) : Activator

    /** The player says something in chat that [text] matches as [match] says. `{message}` is what they said. */
    @Serializable @SerialName("chat")
    data class Chat(
        val text: String,
        val match: TextMatch = TextMatch.EQUALS_IGNORE_CASE,
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator {
        init {
            if (match == TextMatch.REGEX) {
                try {
                    Regex(text)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("'$text' isn't a regular expression: ${e.message}")
                }
            }
        }
    }

    /** The player presses the key that swaps the hands. */
    @Serializable @SerialName("swap-hands")
    data class SwapHands(
        val sneaking: Boolean? = null,
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator

    /**
     * The player clicks a block, or steps on it ([BlockClick.PHYSICAL]). [types] are block keys (`stone_button`) or tags
     * (`#minecraft:buttons`); [at] limits it to one block. With neither, any block matches. `{block}`, `{world}`, `{x}`,
     * `{y}` and `{z}` are the block's.
     */
    @Serializable @SerialName("block")
    data class Block(
        val types: List<String> = [],
        val at: BlockAt? = null,
        val click: BlockClick = BlockClick.RIGHT,
        val sneaking: Boolean? = null,
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator

    /**
     * The player right-clicks an entity: one of [types] (`villager`) and/or called [name]. With neither, any entity
     * matches. `{entity}` is its type and `{entity_name}` its name, empty if it has none.
     */
    @Serializable @SerialName("entity")
    data class Entity(
        val types: List<String> = [],
        val name: String? = null,
        val sneaking: Boolean? = null,
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator

    /**
     * The player clicks an NPC of FancyNpcs: one of [npcs], by the NPC's name or id (any NPC when empty). `{npc}` is its name
     * and `{npc_id}` its id. [cancel] stops the NPC's own actions from running.
     */
    @Serializable @SerialName("npc")
    data class Npc(
        val npcs: List<String> = [],
        val click: Click = Click.ANY,
        val sneaking: Boolean? = null,
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator

    /**
     * The player enters a WorldGuard region: one of [regions] by its id (any region when empty). `{region}` is the id of the
     * region and `{world}` the world. Joining the server inside a region is not entering it. [cancel] has no meaning.
     */
    @Serializable @SerialName("region-enter")
    data class RegionEnter(
        val regions: List<String> = [],
        override val require: Gate? = null,
        override val cancel: Boolean = false,
        override val args: List<String> = [],
    ) : Activator

    /** The player leaves a WorldGuard region, in the same way [RegionEnter] enters one; `{region}` is the region left. */
    @Serializable @SerialName("region-leave")
    data class RegionLeave(
        val regions: List<String> = [],
        override val require: Gate? = null,
        override val cancel: Boolean = false,
        override val args: List<String> = [],
    ) : Activator

    /**
     * An activator another plugin added with [mc.xalitoria.menu.api.XaliMenuApi.registerActivator], written in a menu file as
     * `{ type = "myplugin:name", ... }`. The menu is read with [name] as the type and the keys other than the ones every
     * activator has as one JSON text in [values], which the plugin's type turns into what decides whether it matches. This is
     * not written by hand: it is what the reading of the file makes of such an object.
     */
    @Serializable @SerialName("addon")
    data class Addon(
        val name: String,
        val values: String = "{}",
        override val require: Gate? = null,
        override val cancel: Boolean = true,
        override val args: List<String> = [],
    ) : Activator
}

enum class Click { LEFT, RIGHT, ANY }

/** [PHYSICAL] is stepping on a pressure plate and the like; [ANY] is a left or a right click, not that. */
enum class BlockClick { LEFT, RIGHT, ANY, PHYSICAL }

enum class TextMatch { EQUALS, EQUALS_IGNORE_CASE, CONTAINS, STARTS_WITH, REGEX }

/** One block, by [world] (any world when absent) and coordinates. */
@Serializable
data class BlockAt(val x: Int, val y: Int, val z: Int, val world: String? = null)
