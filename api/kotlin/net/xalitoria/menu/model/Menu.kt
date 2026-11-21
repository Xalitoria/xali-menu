package net.xalitoria.menu.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import net.xalitoria.menu.Action
import kotlin.time.Duration

/**
 * A menu, as stored in `menus/<name>.conf`; the file name is the menu's name.
 *
 * [size] is the number of slots of a chest menu, a multiple of 9 up to 54; other [type]s have a size of their own.
 * Each of [commands] opens the menu, and [args] name the words that must follow it, one word each. A last name that ends
 * in `...` (`reason...`) takes the rest of the line instead. Players type these words, so they are cleaned (no `%`, no
 * control characters) before they are put into `{name}` in text and commands.
 */
@Serializable
data class Menu(
    /** The window title. Colors, placeholders and `{name}` arguments are allowed. */
    val title: String,
    /** The number of slots of a chest menu. */
    @EncodeDefault
    @Within(9, 54)
    val size: Int = 54,
    /** `chest`, or the name of another inventory type, such as `hopper` or `dispenser`. */
    val type: String = "chest",
    /** The commands that open the menu. An entry of several words is not registered as a command. */
    val commands: List<String> = [],
    /** Whether [commands] are registered as commands of the server, or only usable through `open` actions. */
    val register: Boolean = true,
    /** The names of the words that follow a command, in order. */
    val args: List<String> = [],
    /** The usage text shown when a player gives too few or too many words. Built from [args] when absent. */
    val argsUsage: String? = null,
    /**
     * What tab completion offers for each of [args], by name: the words themselves, or `@players` for the names of the
     * players online and `@worlds` for those of the worlds. An argument with no entry gets nothing.
     */
    val complete: Map<String, List<String>> = emptyMap(),
    /** How often the menu draws the items marked `update` again; never when absent. */
    @Serializable(with = DurationAsString::class)
    val refresh: Duration? = null,
    /** Must hold for the menu to open. */
    val open: Gate? = null,
    /** Run when the menu has opened. */
    val onOpen: List<Action> = [],
    /** Run when the player closes the menu or it is replaced by another. */
    val onClose: List<Action> = [],
    /** The items of the menu, by name. The name is used to refer to an item from [matrix] and in messages. */
    val items: Map<String, Item> = emptyMap(),
    /**
     * The menu as a single character grid mapped to [items]: `cells` are rows of at most nine characters and `map` assigns
     * each character to an item name, placing that item in every matching slot besides its own `slot` and `slots`.
     */
    val matrix: MenuMatrix? = null,
    /** What a player can do to open the menu besides its [commands]. */
    val openWith: List<Activator> = [],
    /** Frames that play over the menu once it is open. */
    val animation: Animation? = null,
    /** Makes the menu list the elements of a catalog, a page at a time. */
    val pages: Pages? = null,
    /** How this menu is displayed and interacted with by Bedrock Edition players (through Floodgate or Geyser). */
    val bedrock: BedrockMenu? = null,
) {
    init {
        matrix?.map?.forEach { [character, name] ->
            require(name in items) { "matrix.map gives '$character' the item '$name', which isn't in items" }
        }
    }

    /** The [items] with the slots the [matrix] gives them added to their own. */
    fun placed(): Map<String, Item> {
        val slots = matrix?.slotsByItem() ?: return items
        if (slots.isEmpty()) return items
        return items.mapValues { [name, item] -> slots[name]?.let { item.copy(slots = (item.slots + it).distinct()) } ?: item }
    }

    companion object {
        /** The slots of the player's inventory that a menu can draw in: the hotbar and the three rows above it. */
        val INVENTORY_SLOTS = 0..35
    }
}
