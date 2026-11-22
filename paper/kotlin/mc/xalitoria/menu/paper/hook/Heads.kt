package mc.xalitoria.menu.paper.hook

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.skinsrestorer.api.SkinsRestorerProvider
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player heads. `head:<name>` (or `skull = "<name>"` on a `player_head`) shows an online player's own skin, which already
 * includes what SkinsRestorer applied; for a name that is not online the skin is looked up off the main threads, from
 * SkinsRestorer when it is installed (so a skin set for that name wins), then Paper's profile lookup, and finally Mojang's
 * API directly. `texture:<base64>`, raw base64 texture payloads, and `textures.minecraft.net` URLs need no lookup, and
 * neither does `hdb:<id>`, which takes the texture HeadDatabase already holds.
 */
class Heads(
    private val plugin: Plugin,
    private val skinsRestorer: Boolean,
    /** HeadDatabase's texture (base64) of a head id, or null when it is not installed or the id is not a head. */
    private val hdbTexture: (String) -> String? = { null },
    private val fetchUrl: (String) -> String? = ::httpGet,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Cached {
        data class Found(val profile: PlayerProfile) : Cached

        /** A name nothing could be found for, kept for [MISSING_FOR_MS] so that a temporary lookup failure is retried. */
        data class Missing(val since: Long) : Cached
    }

    private val profiles = ConcurrentHashMap<String, Cached>()
    private val pending = ConcurrentHashMap<String, MutableList<() -> Unit>>()
    @Volatile
    private var skinsRestorerWarned = false

    /**
     * Remembers [player]'s current textured profile so that after they log off (including with a Velocity/SkinsRestorer
     * custom skin or a Bedrock/Floodgate skin), their head renders immediately without an external lookup.
     */
    fun remember(player: Player) {
        val profile = runCatching { player.playerProfile }.getOrNull() ?: return
        if (!profile.hasTextures()) return
        val entry = Cached.Found(profile)
        profiles[player.name.lowercase()] = entry
        profiles[player.uniqueId.toString().lowercase()] = entry
    }

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
            trimmed.startsWith("hdb:") -> hdbTexture(trimmed.removePrefix("hdb:").trim())?.let { texture(it) }
            trimmed.startsWith("head:hdb:") -> hdbTexture(trimmed.removePrefix("head:hdb:").trim())?.let { texture(it) }
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
     * Paper, or Mojang). Call off the server's region threads (for example from the web editor endpoint).
     */
    fun skinUrl(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val profile = onlineProfile(clean) ?: run {
            val key = clean.lowercase()
            val entry = fresh(profiles[key]) ?: run {
                val found = runCatching { lookup(clean) }.getOrNull()
                (if (found != null) Cached.Found(found) else Cached.Missing(now())).also { profiles[key] = it }
            }
            (entry as? Cached.Found)?.profile
        } ?: return null
        profile.textures.skin?.toString()?.let { return it }
        val encoded = profile.properties.firstOrNull { it.name == "textures" }?.value ?: return null
        return extractTextureUrl(encoded)
    }

    private fun onlineProfile(name: String): PlayerProfile? {
        val player = plugin.server.getPlayerExact(name) ?: parseUuid(name)?.let { plugin.server.getPlayer(it) } ?: return null
        val profile = player.playerProfile
        if (profile.hasTextures()) {
            val entry = Cached.Found(profile)
            profiles[player.name.lowercase()] = entry
            profiles[player.uniqueId.toString().lowercase()] = entry
        }
        return profile
    }

    private fun texture(base64: String): PlayerProfile = (profiles.computeIfAbsent("texture:$base64") {
        Cached.Found(plugin.server.createProfile(UUID.nameUUIDFromBytes(base64.toByteArray()), "").apply {
            setProperty(ProfileProperty("textures", base64))
        })
    } as Cached.Found).profile

    internal fun named(name: String, loaded: () -> Unit): PlayerProfile? {
        if (name.isEmpty()) return null
        onlineProfile(name)?.let { return it }
        val key = name.lowercase()
        fresh(profiles[key])?.let { return (it as? Cached.Found)?.profile }
        var started = false
        pending.compute(key) { _, waiters ->
            if (waiters == null) {
                started = true
                mutableListOf(loaded)
            } else {
                waiters.add(loaded)
                waiters
            }
        }
        if (started) plugin.server.asyncScheduler.runNow(plugin) {
            val waiters = try {
                val found = runCatching { lookup(name) }
                    .onFailure { plugin.logger.warning("Looking up the skin of $name failed: ${it.message}") }
                    .getOrNull()
                if (found == null) {
                    plugin.logger.info("No skin found for '$name'; retrying in ${MISSING_FOR_MS / 60_000} minutes.")
                }
                profiles[key] = if (found != null) Cached.Found(found) else Cached.Missing(now())
                pending.remove(key).orEmpty()
            } catch (e: Throwable) {
                val remaining = pending.remove(key).orEmpty()
                plugin.logger.warning("Unexpected error looking up skin of $name: ${e.message}")
                remaining
            }
            for (callback in waiters) {
                runCatching(callback).onFailure { plugin.logger.warning("Redrawing a menu after loading the skin of $name failed: ${it.message}") }
            }
        }
        return null
    }

    private fun lookup(name: String): PlayerProfile? {
        if (skinsRestorer) {
            fromSkinsRestorer(name)?.let { return it }
        }
        // Paper's own lookup first; on offline-mode servers or without a cached texture, fall back directly to Mojang.
        val uuid = parseUuid(name)
        val profile = if (uuid != null) plugin.server.createProfile(uuid) else plugin.server.createProfile(name)
        if (runCatching { profile.complete(true) }.getOrDefault(false) && profile.hasTextures()) return profile
        return mojang(name, uuid)
    }

    /**
     * Queries SkinsRestorer for [name]. Wrapped in [runCatching] because SkinsRestorer throws [IllegalStateException]
     * in Velocity/Bungee proxy mode and [net.skinsrestorer.api.exception.DataRequestException] on upstream errors, which
     * must fall through to Paper and Mojang rather than aborting the lookup.
     */
    private fun fromSkinsRestorer(name: String): PlayerProfile? {
        val api = runCatching { SkinsRestorerProvider.get() }.onFailure { err ->
            if (!skinsRestorerWarned) {
                skinsRestorerWarned = true
                plugin.logger.info("SkinsRestorer API is unavailable on this server (${err.message}); falling back to Mojang for offline player heads.")
            }
        }.getOrNull() ?: return null
        val uuid = parseUuid(name) ?: runCatching { plugin.server.getOfflinePlayerIfCached(name)?.uniqueId }.getOrNull()
        val chosen = uuid?.let { id -> runCatching { api.playerStorage.getSkinForPlayer(id, name).orElse(null) }.getOrNull() }
        val stored = chosen
            ?: runCatching { api.skinStorage.findSkinData(name).map { it.property }.orElse(null) }.getOrNull()
            ?: runCatching { api.skinStorage.getPlayerSkin(name, false).map { it.skinProperty }.orElse(null) }.getOrNull()
            ?: return null
        val profileName = if (VALID_NAME.matches(name)) name else ""
        return plugin.server.createProfile(uuid ?: UUID.nameUUIDFromBytes(name.lowercase().toByteArray()), profileName).apply {
            setProperty(ProfileProperty("textures", stored.value, stored.signature))
        }
    }

    /** The profile Mojang's API has for [name] or [knownUuid] (the uuid, then its signed textures), or null. Blocks: call off region threads. */
    private fun mojang(name: String, knownUuid: UUID? = null): PlayerProfile? {
        val compactId = knownUuid?.toString()?.replace("-", "") ?: run {
            if (!VALID_NAME.matches(name)) return null
            val lookupBody = fetchUrl("https://api.mojang.com/users/profiles/minecraft/$name") ?: return null
            runCatching { Json.parseToJsonElement(lookupBody).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                ?.takeIf { COMPACT_UUID.matches(it) } ?: return null
        }
        val body = fetchUrl("https://sessionserver.mojang.com/session/minecraft/profile/$compactId?unsigned=false") ?: return null
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val resolvedName = root["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: name
        val properties = root["properties"]?.jsonArray ?: return null
        val textures = properties.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "textures" }?.jsonObject ?: return null
        val value = textures["value"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val signature = textures["signature"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val uuid = knownUuid ?: UUID.fromString(compactId.replaceFirst(UUID_DASHES, "$1-$2-$3-$4-$5"))
        return plugin.server.createProfile(uuid, resolvedName).apply {
            setProperty(ProfileProperty("textures", value, signature))
        }
    }

    /** [entry] unless it is a missing name that has been missing long enough to try again. */
    private fun fresh(entry: Cached?): Cached? =
        if (entry is Cached.Missing && now() - entry.since >= MISSING_FOR_MS) null else entry

    fun clear() {
        profiles.clear()
        pending.clear()
    }

    companion object {
        internal const val MISSING_FOR_MS = 5 * 60_000L
        private val VALID_NAME = Regex("[A-Za-z0-9_.]{1,16}")
        private val COMPACT_UUID = Regex("[0-9a-fA-F]{32}")
        private val UUID_DASHES = Regex("(.{8})(.{4})(.{4})(.{4})(.{12})")
        private val URL_IN_JSON = Regex(""""url"\s*:\s*"([^"]+)"""")
        private val HTTP: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        private fun parseUuid(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()

        private fun httpGet(url: String): String? {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "XaliMenu")
                .GET()
                .build()
            val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
            return if (response.statusCode() == 200) response.body() else null
        }

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
