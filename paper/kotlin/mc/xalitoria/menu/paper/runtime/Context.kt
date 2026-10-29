package mc.xalitoria.menu.paper.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.function.UnaryOperator
import me.clip.placeholderapi.PlaceholderAPI
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.ActionsAborted
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.paper.util.verbatim
import mc.xalitoria.util.message.toComponent
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/** PlaceholderAPI behind a seam, so what depends on it can run without a server. */
object Placeholders {
    var resolver: (Player?, String) -> String = { player, text -> PlaceholderAPI.setPlaceholders(player, text) }
}

private val ARGUMENT = Regex("\\{([^{}]+)}")

/** Rendered text that has no placeholder in it, which is every title and lore line that never changes. */
private val STATIC = ConcurrentHashMap<String, Component>()

fun clearTextCache() = STATIC.clear()

/** What a [Context] needs done that depends on the running plugin: the scheduling, and the opening and closing of menus. */
interface Host {
    fun run(actions: List<Action>, ctx: Context)
    fun runLater(player: Player, ticks: Long, task: () -> Unit)
    fun console(command: String)
    fun open(player: Player, menu: String, args: List<String>)
    fun close(player: Player)
    fun refresh(player: Player)
}

/** The [MenuContext] of the plugin: the [player] viewing a [menu], and the [args] typed after its command. */
class Context(
    override val player: Player,
    override val menu: String? = null,
    override val args: Map<String, String> = emptyMap(),
    private val host: Host,
) : MenuContext {
    override fun parse(text: String): String = parse(text, UnaryOperator.identity<String>())

    override fun parse(text: String, escape: UnaryOperator<String>): String {
        // One pass, so an argument that looks like another's `{name}` stays as typed.
        val result = if (args.isEmpty() || '{' !in text) text
        else ARGUMENT.replace(text) { match -> args[match.groupValues[1]]?.let { escape.apply(clean(it)) } ?: match.value }
        return if ('%' in result) Placeholders.resolver(player, result) else result
    }

    /** The result of a text that can't change (no placeholder, no argument) is kept. */
    override fun component(text: String): Component =
        if ('%' in text || args.isNotEmpty() && '{' in text) parse(text, UnaryOperator<String> { it.verbatim() }).toComponent()
        else STATIC.computeIfAbsent(text) { it.toComponent() }

    /** An argument is typed by a player: it must not be able to start a placeholder, or put a line break in a command. */
    private fun clean(value: String): String = value.filter { it != '%' && !it.isISOControl() }

    override fun run(actions: List<Action>) = host.run(actions, this)

    override fun abort(): Nothing = throw ActionsAborted()

    override fun runLater(ticks: Long, task: Runnable) = host.runLater(player, ticks) { task.run() }

    override fun console(command: String) = host.console(command)

    override fun open(menu: String, args: List<String>) = host.open(player, menu, args)

    override fun open(menu: String) = open(menu, emptyList())

    override fun close() = host.close(player)

    override fun refresh() = host.refresh(player)
}
