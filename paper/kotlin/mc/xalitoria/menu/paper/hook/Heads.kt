package mc.xalitoria.menu.paper.hook

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.skinsrestorer.api.SkinsRestorerProvider
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * Player heads. `head:<name>` shows an online player's own skin, which already includes what SkinsRestorer applied; for
 * a name that isn't online the skin is looked up off the main threads, from SkinsRestorer when it is installed (so a
 * skin set for that name wins) and from Mojang otherwise. `texture:<base64>` needs no lookup.
 */
class Heads(private val plugin: Plugin, private val skinsRestorer: Boolean) {
    private val profiles = ConcurrentHashMap<String, PlayerProfile?>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /**
     * The head for [spec], `head:<name>` or `texture:<base64>`. When the skin of an offline name isn't known yet it is
     * a plain head, and [loaded] runs once the skin has arrived.
     */
    fun head(spec: String, loaded: () -> Unit): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        val profile = when {
            spec.startsWith("texture:") -> texture(spec.removePrefix("texture:"))
            else -> named(spec.removePrefix("head:"), loaded)
        }
        if (profile != null) stack.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(profile))
        return stack
    }

    private fun texture(base64: String): PlayerProfile = profiles.computeIfAbsent("texture:$base64") {
        plugin.server.createProfile(UUID.nameUUIDFromBytes(base64.toByteArray()), "").apply {
            setProperty(ProfileProperty("textures", base64))
        }
    }!!

    private fun named(name: String, loaded: () -> Unit): PlayerProfile? {
        plugin.server.getPlayerExact(name)?.let { return it.playerProfile }
        val key = name.lowercase()
        if (profiles.containsKey(key)) return profiles[key]
        if (pending.add(key)) plugin.server.asyncScheduler.runNow(plugin) {
            try {
                profiles[key] = lookup(name)
            } catch (e: Exception) {
                // A skin that can't be found stays a plain head; remembered so it isn't asked for on every redraw.
                profiles[key] = null
            }
            pending.remove(key)
            runCatching(loaded).onFailure { plugin.logger.warning("Redrawing a menu after loading the skin of $name failed: ${it.message}") }
        }
        return null
    }

    private fun lookup(name: String): PlayerProfile? {
        if (skinsRestorer) {
            val property = SkinsRestorerProvider.get().skinStorage.findSkinData(name).map { it.property }.orElse(null)
            if (property != null) {
                return plugin.server.createProfile(UUID.nameUUIDFromBytes(name.lowercase().toByteArray()), name).apply {
                    setProperty(ProfileProperty("textures", property.value, property.signature))
                }
            }
        }
        val profile = plugin.server.createProfile(name)
        return if (profile.complete(true)) profile else null
    }

    fun clear() {
        profiles.clear()
    }
}
