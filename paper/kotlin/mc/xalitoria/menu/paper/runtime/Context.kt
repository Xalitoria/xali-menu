package mc.xalitoria.menu.paper.runtime

import com.xalitoria.util.message.toComponent
import com.xalitoria.util.message.verbatim
import mc.xalitoria.menu.paper.hook.NexoTags
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActionsAborted
import net.xalitoria.menu.MenuContext
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.function.UnaryOperator

/** PlaceholderAPI behind a seam, so what depends on it can run without a server. */
object Placeholders {
    var resolver: (Player?, String) -> String = { player, text -> PlaceholderAPI.setPlaceholders(player, text) }
}

private val ARGUMENT = Regex("\\{([^{}]+)}")

/** Whether [text] has a placeholder in it, of PlaceholderAPI (`%name%`) or an argument (`{name}`): what it comes to is known when it is used. */
fun hasPlaceholder(text: String): Boolean = '%' in text || ARGUMENT.containsMatchIn(text)

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

    /** Moves the open menu's page by [delta] if [relative], otherwise to page [value] (from 1). */
    fun page(player: Player, relative: Boolean, value: Int)
}

/**
 * The [MenuContext] of the plugin: the [player] viewing a [menu], and the [args] typed after its command. [scope] holds the
 * values that go into `{name}` besides the args: the page of a paged menu, and the element being drawn.
 */
class Context(
    override val player: Player,
    override val menu: String? = null,
    override val args: Map<String, String> = emptyMap(),
    private val host: Host,
    private val scope: Map<String, String> = emptyMap(),
) : MenuContext {
    /** What `{name}` is filled in from; the more local a value, the more it counts. */
    private val values: Map<String, String> = if (scope.isEmpty()) args else args + scope

    /** This context with [more] values in scope, such as the element of a paged menu that a slot shows. */
    fun with(more: Map<String, String>): Context = if (more.isEmpty()) this else Context(player, menu, args, host, scope + more)

    override fun parse(text: String): String = parse(text, UnaryOperator.identity<String>())

    override fun parse(text: String, escape: UnaryOperator<String>): String {
        // One pass, so an argument that looks like another's `{name}` stays as typed.
        val result = if (values.isEmpty() || '{' !in text) text
        else ARGUMENT.replace(text) { match -> values[match.groupValues[1]]?.let { escape.apply(clean(it)) } ?: match.value }
        return if ('%' in result) Placeholders.resolver(player, result) else result
    }

    /** The result of a text that cannot change (no placeholder, no argument) is kept. */
    override fun component(text: String): Component =
        if (NexoTags.has(text)) NexoTags.expand(text, player).let { if ('%' in it || values.isNotEmpty() && '{' in it) parse(it, UnaryOperator<String> { v -> v.verbatim() }) else it }.toComponent()
        else if ('%' in text || values.isNotEmpty() && '{' in text) parse(text, UnaryOperator<String> { it.verbatim() }).toComponent()
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

    override fun pageBy(delta: Int) = host.page(player, relative = true, delta)

    override fun pageTo(page: Int) = host.page(player, relative = false, page)
}
