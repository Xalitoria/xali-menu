package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigRenderOptions
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.numbered
import mc.xalitoria.menu.paper.runtime.sortElements
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The elements a catalog lists now, for the editor's preview: what the plugin would draw for the player who asked for the link, instead
 * of the examples the catalog was registered with. The catalog is read the way a menu file is, so a catalog of another plugin works, and
 * [net.xalitoria.menu.Catalog.elements] is called on the player's region thread, as a menu would, since that is the thread it promises.
 */
internal class EditorElements(private val plugin: XaliMenu) {
    sealed interface Result {
        /** [elements] are the first [LIMIT] of [total], sorted as asked and numbered. */
        data class Ok(val elements: List<Map<String, String>>, val total: Int) : Result

        data class Refused(val reason: String) : Result
    }

    /** The elements of [catalog] (the `catalog` object of a menu's `pages`) for [owner], ordered by [sort]. */
    fun list(owner: String, catalog: ConfigObject, sort: String?, descending: Boolean): Result {
        val menu = try {
            format().decode("title = \"\"\npages { catalog = ${catalog.render(ConfigRenderOptions.concise())}, slots = [0], item { material = stone } }")
        } catch (e: Exception) {
            return Result.Refused("The catalog cannot be read: ${e.message}")
        }
        val player = Bukkit.getPlayerExact(owner) ?: return Result.Refused("$owner is not online, and a catalog lists for a player")
        val future = CompletableFuture<List<Map<String, String>>>()
        player.scheduler.run(plugin, {
            try {
                future.complete(menu.pages!!.catalog.elements(Context(player, null, emptyMap(), plugin.actions)))
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }, { future.completeExceptionally(IllegalStateException("$owner left the server")) })
        val elements = try {
            future.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            return Result.Refused("The server did not list the elements in time")
        } catch (e: ExecutionException) {
            return Result.Refused("The catalog failed: ${e.cause?.message ?: e.cause}")
        }
        val sorted = numbered(sortElements(elements, sort, descending))
        return Result.Ok(sorted.take(LIMIT), sorted.size)
    }

    private fun format() = MenuFormat(plugin.registry.module, plugin.registry.externalNames, plugin.registry.activatorNames, plugin.registry.propertyNames)

    companion object {
        /** The most elements sent: enough pages for a preview, not a dump of a catalog of thousands. */
        const val LIMIT = 500
        const val WAIT_SECONDS = 5L
    }
}
