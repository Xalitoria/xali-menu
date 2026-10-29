package mc.xalitoria.menu.api

import java.util.function.UnaryOperator
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * What an [Action] or [Condition] runs against: the player, the menu they are in, and the means to act on their behalf
 * on the right thread.
 */
interface MenuContext {
    val player: Player

    /** The name of the menu this happens in; null for something that isn't in a menu. */
    val menu: String?

    /** The words typed after the command that opened the menu, by the names the menu gives them in `args`. */
    val args: Map<String, String>

    /**
     * [text] with the menu's arguments (`{name}`) and PlaceholderAPI's placeholders filled in. Arguments are typed by
     * players, so what is put in has no `%` and no control characters, which could not otherwise be told from the text
     * of the menu; [escape] is applied to each on top, for text that is read as something other than plain text (as
     * `"` is in JSON).
     */
    fun parse(text: String, escape: UnaryOperator<String>): String

    fun parse(text: String): String

    /**
     * [text] as a component, [parse]d first and colored like chat: `&` codes, hex, CMI gradients and MiniMessage. The
     * arguments in it show as typed: whatever formatting they hold is not applied.
     */
    fun component(text: String): Component

    /**
     * Runs [actions] in order, as if a click had. A failing action is logged and the rest still run; one that calls
     * [abort] ends the list.
     */
    fun run(actions: List<Action>)

    /**
     * Ends the list of actions this is called from: the ones after it don't run. For an action that couldn't do what
     * the rest depends on, such as charging for something that is given next.
     */
    fun abort(): Nothing

    /** Runs [task] on the player's region thread after [ticks], unless they have left by then. */
    fun runLater(ticks: Long, task: Runnable)

    /** Has the console run [command]. On Folia this is handed to the global region, which is where console commands go. */
    fun console(command: String)

    /** Opens [menu] for the player on the next tick, with [args] as the words after the command. */
    fun open(menu: String, args: List<String>)

    fun open(menu: String)

    /** Closes the player's menu on the next tick. */
    fun close()

    /** Draws the player's open menu again. */
    fun refresh()
}
