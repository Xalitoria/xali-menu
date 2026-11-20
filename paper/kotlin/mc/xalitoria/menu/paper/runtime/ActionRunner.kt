package mc.xalitoria.menu.paper.runtime

import com.xalitoria.util.bukkit.runConsoleCommand
import mc.xalitoria.menu.paper.XaliMenu
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActionsAborted
import org.bukkit.entity.Player

/**
 * Runs the [Action]s of a click, open or close, and does for them what has to be done through the server's schedulers.
 * It is called on the player's region thread (Folia) and keeps to it: what has to happen elsewhere, the console's
 * commands, is handed to the scheduler that owns it.
 */
class ActionRunner(private val plugin: XaliMenu) : Host {
    override fun run(actions: List<Action>, ctx: Context) {
        attempt(actions, ctx)
    }

    /** Runs [actions] like [run]. @return whether they all ran to their end, with none aborting or failing */
    fun attempt(actions: List<Action>, ctx: Context): Boolean = runActions(actions, ctx) { action, reason ->
        plugin.logger.warning("Action ${action::class.simpleName} failed in menu '${ctx.menu}': $reason")
    }

    override fun runLater(player: Player, ticks: Long, task: () -> Unit) {
        player.scheduler.runDelayed(plugin, { task() }, null, ticks.coerceAtLeast(1))
    }

    /** Folia has no main thread to run the console on; the global region is where console commands go. */
    override fun console(command: String) {
        plugin.runConsoleCommand(command)
    }

    override fun open(player: Player, menu: String, args: List<String>) {
        // From a slot that takes an item, the window that is open is only replaced once the item has been taken.
        if (!plugin.deposits.afterSettling(player) { plugin.menus.open(player, menu, args) }) plugin.menus.open(player, menu, args)
    }

    override fun close(player: Player) {
        player.scheduler.run(plugin, {
            plugin.bedrockForms.close(player)
            player.closeInventory()
        }, null)
    }

    override fun refresh(player: Player) {
        plugin.menus.view(player)?.draw(force = true) ?: plugin.bedrockForms.refresh(player)
    }

    override fun page(player: Player, relative: Boolean, value: Int) {
        plugin.menus.view(player)?.turn(relative, value) ?: plugin.bedrockForms.turn(player, relative, value)
    }
}

/**
 * Runs [actions] in order. An action that fails is reported to [failed] and the rest still run; one that aborts ends
 * the list. @return whether every action ran to its end: false when one aborted or failed
 */
fun runActions(actions: List<Action>, ctx: Context, failed: (Action, String?) -> Unit): Boolean {
    var complete = true
    for (action in actions) {
        try {
            action.run(ctx)
        } catch (_: ActionsAborted) {
            return false
        } catch (e: Exception) {
            complete = false
            failed(action, e.message)
        } catch (e: LinkageError) {
            // A plugin whose API this was built against is installed in another version.
            complete = false
            failed(action, "${e.message} is missing")
        }
    }
    return complete
}
