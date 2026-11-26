package mc.xalitoria.menu.paper.hook

import com.nexomc.nexo.api.events.NexoItemsLoadedEvent
import com.nexomc.nexo.api.events.resourcepack.NexoPackFinishedEvent
import com.nexomc.nexo.api.events.resourcepack.NexoPackUploadEvent
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.runtime.clearTextCache
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/**
 * Keeps in-game menus and active web-editor tabs in sync whenever Nexo reloads its items or rebuilds/uploads its resource pack.
 */
class NexoListener(private val plugin: XaliMenu) : Listener {
    @EventHandler
    fun onItemsLoaded(event: NexoItemsLoadedEvent) {
        clearTextCache()
        plugin.items.clearWarnings()
        plugin.editor.api.notifyPackUpdated("Nexo")
    }

    @EventHandler
    fun onPackFinished(event: NexoPackFinishedEvent) {
        clearTextCache()
        plugin.editor.api.notifyPackUpdated("Nexo")
    }

    @EventHandler
    fun onPackUpload(event: NexoPackUploadEvent) {
        plugin.editor.api.notifyPackUpdated("Nexo")
    }
}
