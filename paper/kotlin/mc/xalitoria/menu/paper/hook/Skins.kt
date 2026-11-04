package mc.xalitoria.menu.paper.hook

import net.skinsrestorer.api.SkinsRestorerProvider
import net.skinsrestorer.api.property.SkinVariant
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * The skin of a player, through SkinsRestorer, which remembers it for the next time they join. Its classes are only
 * touched when the plugin is here, so ask [available] first.
 */
class Skins(private val plugin: Plugin) {
    val available: Boolean get() = plugin.server.pluginManager.isPluginEnabled("SkinsRestorer")

    /**
     * Gives [player] the skin of the player [source] has or, if [source] is a link to an image, that picture. Looking it up
     * can take a while, so it is done off the region threads, and the skin is put on once it is known. A skin that cannot be
     * found is [failed] with the reason. Anything done with a player happens on their own thread.
     */
    fun set(player: Player, source: String, slim: Boolean, failed: (String) -> Unit) {
        plugin.server.asyncScheduler.runNow(plugin) {
            try {
                val found = SkinsRestorerProvider.get().skinStorage
                    .findOrCreateSkinData(source, if (slim) SkinVariant.SLIM else SkinVariant.CLASSIC)
                    .orElse(null)
                if (found == null) {
                    failed("there is no skin '$source'")
                    return@runNow
                }
                apply(player) { SkinsRestorerProvider.get().playerStorage.setSkinIdOfPlayer(player.uniqueId, found.identifier) }
            } catch (e: Exception) {
                failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Takes the chosen skin off [player], so they have their own again. */
    fun reset(player: Player, failed: (String) -> Unit) {
        plugin.server.asyncScheduler.runNow(plugin) {
            try {
                apply(player) { SkinsRestorerProvider.get().playerStorage.removeSkinIdOfPlayer(player.uniqueId) }
            } catch (e: Exception) {
                failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private fun apply(player: Player, store: () -> Unit) {
        store()
        player.scheduler.run(plugin, { SkinsRestorerProvider.get().getSkinApplier(Player::class.java).applySkin(player) }, null)
    }
}
