package mc.xalitoria.menu.paper.hook

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.skinsrestorer.api.SkinsRestorerProvider
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player heads. `head:<name>` (or `skull = "<name>"` on a `player_head`) shows an online player's own skin, which already
 * includes what SkinsRestorer applied; for a name that is not online the skin is looked up off the main threads, from
 * SkinsRestorer when it is installed (so a skin set for that name wins) and from Mojang otherwise. `texture:<base64>`,
 * raw base64 texture payloads, and `textures.minecraft.net` URLs need no lookup.
 */
class Heads(private val plugin: Plugin, private val skinsRestorer: Boolean) {
    private val profiles = ConcurrentHashMap<String, PlayerProfile?>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /**
     * The head for [spec], `head:<name>`, `texture:<base64>`, a texture URL, raw base64, or a player name. When the skin
     * of an offline name is not known yet it is a plain head, and [loaded] runs once the skin has arrived.
     */
    fun head(spec: String, loaded: () -> Unit): ItemStack =
        ItemStack(Material.PLAYER_HEAD).also { apply(it, spec, loaded) }

    /** Applies the player head profile for [spec] onto [stack] when it is a `player_head`. */
    fun apply(stack: ItemStack, spec: String, loaded: () -> Unit) {
        if (stack.type != Material.PLAYER_HEAD) return
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return
        val profile = when {
            trimmed.startsWith("texture:") -> texture(trimmed.removePrefix("texture:").trim())
            trimmed.startsWith("head:") -> named(trimmed.removePrefix("head:").trim(), loaded)
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> texture(encodeUrl(trimmed))
            looksLikeBase64Texture(trimmed) -> texture(trimmed)
            else -> named(trimmed, loaded)
        }
        if (profile != null) stack.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(profile))
    }

    /**
     * Synchronously resolves the `textures.minecraft.net` skin URL for player [name] (online player, cache, SkinsRestorer,
     * or Mojang). Call off the server's region threads (for example from the web editor endpoint).
     */
    fun skinUrl(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val profile = plugin.server.getPlayerExact(clean)?.playerProfile ?: run {
            val key = clean.lowercase()
            if (profiles.containsKey(key)) profiles[key]
            else runCatching { lookup(clean) }.getOrNull().also { profiles[key] = it }
        } ?: return null
        profile.textures.skin?.toString()?.let { return it }
        val encoded = profile.properties.firstOrNull { it.name == "textures" }?.value ?: return null
        return extractTextureUrl(encoded)
    }

    private fun texture(base64: String): PlayerProfile = profiles.computeIfAbsent("texture:$base64") {
        plugin.server.createProfile(UUID.nameUUIDFromBytes(base64.toByteArray()), "").apply {
            setProperty(ProfileProperty("textures", base64))
        }
    }!!

    private fun named(name: String, loaded: () -> Unit): PlayerProfile? {
        if (name.isEmpty()) return null
        plugin.server.getPlayerExact(name)?.let { return it.playerProfile }
        val key = name.lowercase()
        if (profiles.containsKey(key)) return profiles[key]
        if (pending.add(key)) plugin.server.asyncScheduler.runNow(plugin) {
            try {
                profiles[key] = lookup(name)
            } catch (e: Exception) {
                // A skin that cannot be found stays a plain head; remembered so it is not asked for on every redraw.
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

    companion object {
        private val URL_IN_JSON = Regex(""""url"\s*:\s*"([^"]+)"""")

        /** Encodes a `textures.minecraft.net` skin URL into the base64 JSON payload Minecraft expects in `textures`. */
        fun encodeUrl(url: String): String =
            Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"$url"}}}""".toByteArray(Charsets.UTF_8))

        /** Whether [text] is a base64-encoded skin texture payload rather than a player name. */
        fun looksLikeBase64Texture(text: String): Boolean =
            text.length >= 40 && extractTextureUrl(text) != null

        /** Extracts the skin URL from a base64-encoded Minecraft `textures` profile property, or null. */
        fun extractTextureUrl(base64: String): String? = runCatching {
            val decoded = Base64.getDecoder().decode(base64).decodeToString()
            URL_IN_JSON.find(decoded)?.groupValues?.get(1)
        }.getOrNull()
    }
}
