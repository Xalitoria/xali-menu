package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.ActionsAborted
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.entity.Player

/**
 * Runs the [Action]s of a click, open or close, and does for them what has to be done through the server's schedulers.
 * It is called on the player's region thread (Folia) and keeps to it: what has to happen elsewhere, the console's
 * commands, is handed to the scheduler that owns it.
 */
class ActionRunner(private val plugin: XaliMenu) : Host {
    override fun run(actions: List<Action>, ctx: Context) = runActions(actions, ctx) { action, reason ->
        plugin.logger.warning("Action ${action::class.simpleName} failed in menu '${ctx.menu}': $reason")
    }

    override fun runLater(player: Player, ticks: Long, task: () -> Unit) {
        player.scheduler.runDelayed(plugin, { task() }, null, ticks.coerceAtLeast(1))
    }

    /** Folia has no main thread to run the console on; the global region is where console commands go. */
    override fun console(command: String) {
        plugin.server.globalRegionScheduler.run(plugin) { plugin.server.dispatchCommand(plugin.server.consoleSender, command) }
    }

    override fun open(player: Player, menu: String, args: List<String>) = plugin.menus.open(player, menu, args)

    override fun close(player: Player) {
        player.scheduler.run(plugin, { player.closeInventory() }, null)
    }

    override fun refresh(player: Player) {
        plugin.menus.view(player)?.draw(force = true)
    }
}

/**
 * Runs [actions] in order. An action that fails is reported to [failed] and the rest still run; one that aborts ends
 * the list.
 */
fun runActions(actions: List<Action>, ctx: Context, failed: (Action, String?) -> Unit) {
    for (action in actions) {
        try {
            action.run(ctx)
        } catch (_: ActionsAborted) {
            return
        } catch (e: Exception) {
            failed(action, e.message)
        } catch (e: LinkageError) {
            // A plugin whose API this was built against is installed in another version.
            failed(action, "${e.message} is missing")
        }
    }
}
