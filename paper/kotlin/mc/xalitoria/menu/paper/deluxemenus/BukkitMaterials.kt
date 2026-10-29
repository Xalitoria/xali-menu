package mc.xalitoria.menu.paper.deluxemenus

import org.bukkit.Bukkit
import org.bukkit.Material

/** [Materials] answered by the running server, whose legacy conversion is what DeluxeMenus configs of old servers need. */
@Suppress("DEPRECATION")
object BukkitMaterials : Materials {
    private val ids: Map<Int, Material> by lazy { Material.entries.filter { it.isLegacy }.associateBy { it.id } }

    override fun legacy(id: Int, data: Int): String? = ids[id]?.let { convert(it, data) }

    override fun legacy(name: String, data: Int): String? =
        Material.getMaterial("LEGACY_" + name.uppercase())?.let { convert(it, data) }

    override fun exists(name: String): Boolean = Material.matchMaterial(name) != null

    private fun convert(legacy: Material, data: Int): String? {
        val modern = Bukkit.getUnsafe().fromLegacy(legacy.getNewData(data.toByte()))
        return if (modern.isAir && !legacy.isAir) null else modern.key.key
    }
}
