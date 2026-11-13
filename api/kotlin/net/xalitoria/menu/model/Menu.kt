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
    /** The items of the menu, by name. The name is only used to refer to an item from [legend] and in messages. */
    val items: Map<String, Item> = emptyMap(),
    /**
     * Items drawn in the player's own inventory, the rows under the menu, while it is open. Their `slot` and `slots` are the
     * slots of the inventory as the game numbers them: 0 to 8 the hotbar, 9 to 35 the three rows above it. They take the
     * place of what is there, which comes back when the menu closes. They do everything the items of the menu do (`view`,
     * `update`, clicks, priority), but nothing can be moved in or out.
     */
    val inventory: Map<String, Item> = emptyMap(),
    /**
     * The menu as it looks, in rows of nine characters, for items to be placed by: each character of the [legend] puts the
     * item it names in every slot where it stands, besides the item's own `slot` and `slots`. A row of fewer characters
     * ends in blanks, and a character the legend does not know is a blank.
     */
    val layout: List<String> = [],
    /** The character of the [layout] to the name of the item in [items] that goes where it stands. */
    val legend: Map<String, String> = emptyMap(),
    /** What a player can do to open the menu besides its [commands]. */
    val openWith: List<Activator> = [],
    /** Frames that play over the menu once it is open. */
    val animation: Animation? = null,
    /** Makes the menu list the elements of a catalog, a page at a time. */
    val pages: Pages? = null,
) {
    init {
        require(layout.all { it.length <= 9 }) { "a row of layout has more than 9 characters" }
        require(legend.keys.all { it.length == 1 }) { "the legend names single characters, not '${legend.keys.first { it.length != 1 }}'" }
        legend.forEach { [character, name] -> require(name in items) { "the legend gives '$character' the item '$name', which isn't in items" } }
    }

    /** The [items] with the slots the [layout] gives them added to their own. */
    fun placed(): Map<String, Item> {
        if (layout.isEmpty() || legend.isEmpty()) return items
        val slots = layout.flatMapIndexed { row, text ->
            text.mapIndexedNotNull { column, c -> legend[c.toString()]?.let { it to row * 9 + column } }
        }.groupBy({ it.first }, { it.second })
        return items.mapValues { [name, item] -> slots[name]?.let { item.copy(slots = (item.slots + it).distinct()) } ?: item }
    }

    companion object {
        /** The slots of the player's inventory that a menu can draw in: the hotbar and the three rows above it. */
        val INVENTORY_SLOTS = 0..35
    }
}
