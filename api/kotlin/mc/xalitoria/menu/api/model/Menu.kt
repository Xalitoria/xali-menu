package mc.xalitoria.menu.api.model

import kotlin.time.Duration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Action

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
    val title: String,
    @EncodeDefault
    val size: Int = 54,
    val type: String = "chest",
    val commands: List<String> = [],
    /** Whether [commands] are registered as commands of the server, or only usable through `open` actions. */
    val register: Boolean = true,
    val args: List<String> = [],
    val argsUsage: String? = null,
    /** How often the menu draws the items marked `update` again; never when absent. */
    @Serializable(with = DurationAsString::class)
    val refresh: Duration? = null,
    val open: Gate? = null,
    val onOpen: List<Action> = [],
    val onClose: List<Action> = [],
    val items: Map<String, Item> = emptyMap(),
)
