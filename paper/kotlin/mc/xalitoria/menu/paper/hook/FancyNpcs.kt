package mc.xalitoria.menu.paper.hook

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.runtime.ActivatorListener
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/**
 * Lets the `npc` activators hear the clicks on NPCs of FancyNpcs. Its event is read by name, so nothing here is linked to its
 * API: the plugin may be missing, or a different version. Make this only when FancyNpcs is enabled.
 */
class FancyNpcs(private val plugin: XaliMenu, private val activators: ActivatorListener) : Listener {
    /** Starts listening. @return false if this version of FancyNpcs doesn't have the event it is heard by */
    fun register(): Boolean = try {
        val type = Class.forName("de.oliver.fancynpcs.api.events.NpcInteractEvent", true, plugin.server.pluginManager.getPlugin("FancyNpcs")!!.javaClass.classLoader)
        val npc = type.getMethod("getNpc")
        val data = npc.returnType.getMethod("getData")
        val name = data.returnType.getMethod("getName")
        val id = data.returnType.getMethod("getId")
        val kind = type.getMethod("getInteractionType")
        val who = type.getMethod("getPlayer")
        val executor = EventExecutor { _, event ->
            if (!type.isInstance(event)) return@EventExecutor
            val clicked = data.invoke(npc.invoke(event))
            val player = who.invoke(event) as Player
            val left = when ((kind.invoke(event) as Enum<*>).name) {
                "LEFT_CLICK" -> true
                "RIGHT_CLICK" -> false
                "ANY_CLICK" -> null
                else -> return@EventExecutor
            }
            activators.onNpc(player, event as Cancellable, name.invoke(clicked) as String, id.invoke(clicked) as String, left)
        }
        @Suppress("UNCHECKED_CAST")
        plugin.server.pluginManager.registerEvent(type as Class<out Event>, this, EventPriority.HIGH, executor, plugin, true)
        true
    } catch (e: ReflectiveOperationException) {
        plugin.logger.warning("FancyNpcs is here, but its click event isn't what was expected, so npc activators won't work: $e")
        false
    }
}
