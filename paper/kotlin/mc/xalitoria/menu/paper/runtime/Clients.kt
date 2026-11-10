package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * What a player's client can do, as far as it differs from the server's own version. Clients older than the server can only
 * join through ViaVersion, so without it every client is the server's version.
 */
object Clients {
    /** The protocol version of Minecraft 1.21.6, the first with dialogs. */
    const val DIALOGS = 771

    /** The protocol version of [player]'s client, or null when nothing says it differs from the server's. */
    var versionOf: (Player) -> Int? = ::viaVersion

    /** Whether [player]'s client can show a dialog. */
    fun dialogs(player: Player): Boolean = (versionOf(player) ?: return true) >= DIALOGS

    private fun viaVersion(player: Player): Int? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) return null
        // The class is only there with the plugin; a failure of any kind leaves the client as the server's version.
        val version = runCatching { com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player.uniqueId) }
            .onFailure { debug("ViaVersion couldn't say which version {0} plays on: {1}", player.name, it) }
            .getOrNull()
        return version?.takeIf { it > 0 }
    }
}
