package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.bedrock.ClientProfile
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * What a player's client can do, as far as it differs from the server's own Java version. Clients older than the server
 * can only join through ViaVersion, and Bedrock clients join through Floodgate or Geyser.
 */
object Clients {
    /** The protocol version of Minecraft 1.17, the first whose click packets carry changed slots and a state id. */
    const val LEGACY_CLICK_PROTOCOL = 755

    /** The protocol version of Minecraft 1.21.6, the first with dialogs. */
    const val DIALOGS = 771

    /** The protocol version of [player]'s client, or null when nothing says it differs from the server's. */
    var versionOf: (Player) -> Int? = ::viaVersion

    /** The detected client profile (Java or Bedrock, device OS, input mode, UI profile) of [player]. */
    var profileOf: (Player) -> ClientProfile = { ClientProfile.JAVA }

    /** Returns the [ClientProfile] of [player]. */
    fun profile(player: Player): ClientProfile = profileOf(player)

    /** Whether [player] is connected from Minecraft: Bedrock Edition. */
    fun isBedrock(player: Player): Boolean = profileOf(player).isBedrock

    /** Whether [player]'s client speaks a pre-1.17 protocol (< 755, such as 1.8.9–1.16.5 through ViaVersion/ViaBackwards/ViaRewind). */
    fun isLegacyInventoryClient(player: Player): Boolean {
        val protocol = versionOf(player) ?: return false
        return protocol < LEGACY_CLICK_PROTOCOL
    }

    /** Whether [player]'s client can show a Java 1.21.6+ dialog. Bedrock clients do not support Java dialogs. */
    fun dialogs(player: Player): Boolean {
        if (isBedrock(player)) return false
        return (versionOf(player) ?: return true) >= DIALOGS
    }

    /** Installed ViaVersion family plugin versions for startup and debug diagnostics, or null when none are enabled. */
    fun viaSummary(): String? {
        val pm = Bukkit.getPluginManager()
        val found = listOf("ViaVersion", "ViaBackwards", "ViaRewind").mapNotNull { name ->
            pm.getPlugin(name)?.takeIf { it.isEnabled }?.let { "$name ${it.pluginMeta.version}" }
        }
        return found.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun viaVersion(player: Player): Int? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) return null
        // The class is only there with the plugin; a failure of any kind leaves the client as the server's version.
        val version = runCatching { com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player.uniqueId) }
            .onFailure { debug("ViaVersion couldn't say which version {0} plays on: {1}", player.name, it) }
            .getOrNull()
        return version?.takeIf { it > 0 }
    }
}
