package mc.xalitoria.menu.paper.editor

import io.papermc.paper.datacomponent.DataComponentTypes
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.extension

/**
 * What the editor needs from the plugins that make a resource pack: the pack itself, the names of their glyphs, and what their
 * custom items are. All three plugins that are supported write a normal pack, so the editor reads it like any other; what it
 * cannot know is which character is which glyph, and which item model an id stands for, so those come from here.
 *
 * Nexo and Oraxen keep the glyphs in the yml files of their `glyphs` folder, each with a `char`. ItemsAdder chooses the characters
 * itself: the names come from the `font_images` of its content files, and each character is asked of its API.
 */
internal class ResourcePacks(private val plugin: XaliMenu) {
    /**
     * The items a menu can use: every item of the game, and the items of each source that lists them, by prefix (`nexo` to `ruby`...).
     * Ids are what follows the prefix.
     */
    fun catalog(): ItemCatalog {
        val vanilla = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ITEM)
            .map { it.key().value() }.sorted()
        val custom = plugin.registry.sources.entries.mapNotNull { entry ->
            val ids = (entry.value as? mc.xalitoria.menu.paper.hook.ListsItems)?.let { runCatching { it.ids() }.getOrNull() } ?: return@mapNotNull null
            entry.key to ids
        }.toMap()
        return ItemCatalog(vanilla, custom)
    }

    /** Live game registries directly from the running server (including datapack entries), for the editor's dropdowns. */
    fun registries(): Map<String, List<String>> {
        val access = io.papermc.paper.registry.RegistryAccess.registryAccess()
        fun <T : org.bukkit.Keyed> keys(key: io.papermc.paper.registry.RegistryKey<T>): List<String> =
            runCatching { access.getRegistry(key).map { it.key().value() }.sorted() }.getOrDefault(emptyList())
        val particles = runCatching { org.bukkit.Registry.PARTICLE_TYPE.map { it.key.key }.sorted() }.getOrDefault(emptyList())
        return mapOf(
            "sound" to keys(io.papermc.paper.registry.RegistryKey.SOUND_EVENT),
            "particle" to particles,
            "enchantment" to keys(io.papermc.paper.registry.RegistryKey.ENCHANTMENT),
            "effect" to keys(io.papermc.paper.registry.RegistryKey.MOB_EFFECT),
            "attribute" to keys(io.papermc.paper.registry.RegistryKey.ATTRIBUTE),
            "banner-pattern" to keys(io.papermc.paper.registry.RegistryKey.BANNER_PATTERN),
            "potion-type" to keys(io.papermc.paper.registry.RegistryKey.POTION),
            "trim-pattern" to keys(io.papermc.paper.registry.RegistryKey.TRIM_PATTERN),
            "trim-material" to keys(io.papermc.paper.registry.RegistryKey.TRIM_MATERIAL),
            "entity-type" to keys(io.papermc.paper.registry.RegistryKey.ENTITY_TYPE),
        ).filterValues { it.isNotEmpty() }
    }

    /**
     * The color a potion shows, for each of [specs] (a base potion type, and custom effects): the average of the colors of its effects, each
     * as often as its level, as the game works it out; the color of water when there are no effects. Null for a spec that names nothing.
     */
    fun potionColors(specs: List<Pair<String?, List<Pair<String, Int>>>>): List<Int?> = specs.map { spec ->
        val type = spec.first
        val custom = spec.second
        val registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
        val base = type?.let { name -> org.bukkit.NamespacedKey.fromString(name.lowercase())?.let { registry.getRegistry(io.papermc.paper.registry.RegistryKey.POTION).get(it) } }
        val effects = (base?.potionEffects?.map { it.type to it.amplifier }.orEmpty()) + custom.mapNotNull { entry ->
            val name = entry.first
            val level = entry.second
            org.bukkit.NamespacedKey.fromString(name.lowercase())?.let { registry.getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT).get(it) }?.let { it to level }
        }
        if (type != null && base == null && custom.isEmpty()) return@map null
        var red = 0L; var green = 0L; var blue = 0L; var weight = 0L
        for (entry in effects) {
            val effect = entry.first
            val level = entry.second
            val color = effect.color
            val w = (level + 1).toLong()
            red += color.red * w; green += color.green * w; blue += color.blue * w; weight += w
        }
        if (weight == 0L) 0x385DC6 else ((red / weight).toInt() shl 16) or ((green / weight).toInt() shl 8) or (blue / weight).toInt()
    }

    @Volatile
    private var cachedHash: Triple<Path, Long, String>? = null

    /** The pack file: the configured one, otherwise the first of the supported plugins that has one. */
    fun pack(): Path? {
        val configured = plugin.config.data.editor.pack
        if (configured.isNotBlank()) return Path.of(configured).takeIf { it.exists() }
        return candidates().firstOrNull { it.exists() }
    }

    /**
     * SHA-256 hex digest of the local [pack] file (cached by path + mtime + size) so the browser can reuse cached
     * resource-pack chunks across `/xmenu edit` sessions without re-downloading unchanged packs through the relay.
     */
    fun packHash(): String? {
        val file = pack() ?: return null
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrNull() ?: return null
        cachedHash?.let { if (it.first == file && it.second == stamp) return it.third }
        val digest = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        cachedHash = Triple(file, stamp, digest)
        return digest
    }

    /**
     * Where the pack is hosted for the players, if it is: `editor.pack-url`, else what Nexo says it sends them (an S3 or LobFile address, its
     * own host or yours). The editor downloads from there, so a big pack does not have to go through this server.
     */
    fun packUrl(): String? {
        val configured = plugin.config.data.editor.packUrl.trim()
        if (configured.isNotEmpty()) return configured.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (plugin.server.pluginManager.isPluginEnabled("Nexo")) {
            return mc.xalitoria.menu.paper.hook.NexoPackHost.url()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
        if (plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) {
            return mc.xalitoria.menu.paper.hook.ItemsAdderHost.url()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
        return null
    }

    private fun candidates(): List<Path> {
        val plugins = plugin.server.pluginsFolder.toPath()
        return listOf(
            plugins.resolve("Nexo/pack/pack.zip"),
            plugins.resolve("Oraxen/pack/pack.zip"),
            plugins.resolve("ItemsAdder/output/generated.zip"),
        )
    }

    /** The glyphs the plugins define, as `{name, char, plugin}`. */
    fun glyphs(): List<Glyph> {
        val plugins = plugin.server.pluginsFolder.toPath()
        val found = mutableListOf<Glyph>()
        for (name in listOf("Nexo", "Oraxen")) {
            val folder = plugins.resolve("$name/glyphs")
            if (!Files.isDirectory(folder)) continue
            Files.walk(folder).use { files ->
                files.filter { it.extension == "yml" }.forEach { file ->
                    val yaml = YamlConfiguration.loadConfiguration(file.toFile())
                    for (key in yaml.getKeys(false)) {
                        val character = yaml.getString("$key.char")?.takeIf { it.isNotEmpty() }
                            ?: yaml.getStringList("$key.chars").firstOrNull()?.takeIf { it.isNotEmpty() }
                            ?: continue
                        val font = yaml.getString("$key.font")?.takeIf { it.isNotBlank() }
                        found += Glyph(key, character, name, font)
                    }
                }
            }
        }
        if (plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) found += itemsAdder()
        return found
    }

    /** The ids of the font images a plugin's content files declare, `namespace:id`, found by [fontImageIds]. */
    private fun itemsAdder(): List<Glyph> {
        val contents = plugin.server.pluginsFolder.toPath().resolve("ItemsAdder/contents")
        if (!Files.isDirectory(contents)) return emptyList()
        val ids = Files.walk(contents).use { files ->
            files.filter { it.extension == "yml" }.toList().flatMap { file ->
                runCatching { fontImageIds(YamlConfiguration.loadConfiguration(file.toFile())) }.getOrDefault(emptyList())
            }
        }
        return ids.distinct().mapNotNull { id ->
            val text = mc.xalitoria.menu.paper.hook.ItemsAdderHost.glyph(id) ?: return@mapNotNull null
            Glyph(id, text, "ItemsAdder")
        }
    }

    /**
     * What the items in [materials] are: a custom item (`nexo:ruby`) is the item it is made of, its item model, and its custom model
     * data, which is how the pack tells them apart; any item says how much it wears (`maxDamage`, 0 for none), which the durability
     * bar needs. Built on the global region's thread, which owns no world, since the plugins that make the items were not written
     * for any other.
     */
    fun items(materials: List<String>): Map<String, ItemInfo> {
        val done = CompletableFuture<Map<String, ItemInfo>>()
        plugin.server.globalRegionScheduler.run(plugin) {
            done.complete(materials.mapNotNull { material ->
                val prefix = material.substringBefore(':', "")
                val source = plugin.registry.sources[prefix.lowercase()]
                val stack = when {
                    source != null -> runCatching { source.item(material.substringAfter(':')) }.getOrNull()
                    // A vanilla item is only asked about for what the pack cannot say: how much it wears.
                    ':' !in material || prefix == "minecraft" -> org.bukkit.Material.matchMaterial(material)?.takeIf { it.isItem && !it.isLegacy }?.let { org.bukkit.inventory.ItemStack(it) }
                    else -> null
                } ?: return@mapNotNull null
                material to ItemInfo(
                    material = stack.type.key.key,
                    itemModel = (stack.getData(DataComponentTypes.ITEM_MODEL)?.asString()
                        ?: if (prefix.equals("itemsadder", true)) mc.xalitoria.menu.paper.hook.ItemsAdderHost.model(material.substringAfter(':')) else null)
                        .takeIf { source != null },
                    customModelData = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats()?.map { it.toDouble() },
                    maxDamage = stack.getData(DataComponentTypes.MAX_DAMAGE) ?: 0,
                )
            }.toMap())
        }
        return try {
            done.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/** The font images a ItemsAdder content file declares, as `namespace:id`: the ids under `font_images`, in the `info.namespace` of the file. */
internal fun fontImageIds(yaml: org.bukkit.configuration.ConfigurationSection): List<String> {
    val namespace = yaml.getString("info.namespace")?.takeIf { it.isNotBlank() } ?: return emptyList()
    val images = yaml.getConfigurationSection("font_images") ?: return emptyList()
    return images.getKeys(false).map { "$namespace:$it" }
}
