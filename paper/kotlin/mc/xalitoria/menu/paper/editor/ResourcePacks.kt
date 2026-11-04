package mc.xalitoria.menu.paper.editor

import io.papermc.paper.datacomponent.DataComponentTypes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.extension
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.configuration.file.YamlConfiguration

/**
 * What the editor needs from the plugins that make a resource pack: the pack itself, the names of their glyphs, and what their
 * custom items are. All three plugins that are supported write a normal pack, so the editor reads it like any other; what it
 * cannot know is which character is which glyph, and which item model an id stands for, so those come from here.
 *
 * Nexo and Oraxen keep the glyphs in the yml files of their `glyphs` folder, each with a `char`. ItemsAdder keeps them in its own registry, asked through
 * its API by name, so nothing here is linked to it.
 */
internal class ResourcePacks(private val plugin: XaliMenu) {
    /** The pack file: the configured one, otherwise the first of the supported plugins that has one. */
    fun pack(): Path? {
        val configured = plugin.config.data.editor.pack
        if (configured.isNotBlank()) return Path.of(configured).takeIf { it.exists() }
        return candidates().firstOrNull { it.exists() }
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
    fun glyphs(): List<Map<String, String>> {
        val plugins = plugin.server.pluginsFolder.toPath()
        val found = mutableListOf<Map<String, String>>()
        for (name in listOf("Nexo", "Oraxen")) {
            val folder = plugins.resolve("$name/glyphs")
            if (!Files.isDirectory(folder)) continue
            Files.walk(folder).use { files ->
                files.filter { it.extension == "yml" }.forEach { file ->
                    val yaml = YamlConfiguration.loadConfiguration(file.toFile())
                    for (key in yaml.getKeys(false)) {
                        val character = yaml.getString("$key.char")?.takeIf { it.isNotEmpty() } ?: continue
                        found += mapOf("name" to key, "char" to character, "plugin" to name)
                    }
                }
            }
        }
        if (plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) found += itemsAdder()
        return found
    }

    private fun itemsAdder(): List<Map<String, String>> = try {
        val api = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper")
        val ids = api.getMethod("getNamespacedIdsInRegistry").invoke(null) as Collection<*>
        val instance = api.getMethod("instance", String::class.java)
        val string = api.getMethod("getString")
        ids.mapNotNull { id ->
            val text = string.invoke(instance.invoke(null, id)) as? String ?: return@mapNotNull null
            mapOf("name" to id.toString(), "char" to text, "plugin" to "ItemsAdder")
        }
    } catch (e: Exception) {
        plugin.logger.warning("The glyphs of ItemsAdder could not be read: ${e.message}")
        emptyList()
    }

    /**
     * What the custom items in [materials] (`nexo:ruby`) are made of: the item they are, their item model, and their custom model
     * data, which is how the pack tells them apart. Built on the global region's thread, which owns no world, since the plugins
     * that make the items were not written for any other.
     */
    fun items(materials: List<String>): Map<String, Map<String, Any?>> {
        val done = CompletableFuture<Map<String, Map<String, Any?>>>()
        plugin.server.globalRegionScheduler.run(plugin) {
            done.complete(materials.mapNotNull { material ->
                val prefix = material.substringBefore(':', "")
                val source = plugin.registry.sources[prefix.lowercase()] ?: return@mapNotNull null
                val stack = runCatching { source.item(material.substringAfter(':')) }.getOrNull() ?: return@mapNotNull null
                material to mapOf(
                    "material" to stack.type.key.key,
                    "itemModel" to stack.getData(DataComponentTypes.ITEM_MODEL)?.asString(),
                    "customModelData" to stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats()?.map { it.toDouble() },
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
