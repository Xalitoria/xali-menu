package mc.xalitoria.menu.paper.hook

import com.nexomc.nexo.api.NexoItems
import dev.lone.itemsadder.api.CustomStack
import me.arcaniax.hdb.api.HeadDatabaseAPI
import net.xalitoria.menu.ItemSource
import org.bukkit.inventory.ItemStack

/** An item source that can list its items, which the editor offers in its search. */
interface ListsItems {
    /** The ids that follow the prefix, such as `ruby` for `nexo:ruby`. */
    fun ids(): List<String>
}

/** ItemsAdder's items, as `itemsadder:<namespace>:<id>`. */
class ItemsAdderSource : ItemSource, ListsItems {
    override val prefix = "itemsadder"
    override fun item(id: String): ItemStack? = CustomStack.getInstance(id)?.itemStack
    override fun ids(): List<String> = CustomStack.getNamespacedIdsInRegistry().map { it.toString() }.sorted()
}

/** What ItemsAdder can say about its resource pack and its pictures, which its API gives only on a running server. */
object ItemsAdderHost {
    /** The address ItemsAdder sends its pack from, or null while the pack is still being made or the API is not the one this was written for. */
    fun url(): String? = try {
        dev.lone.itemsadder.api.ItemsAdder.getPackUrl(false)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }

    /** The item model ItemsAdder gives [id] (`namespace:id`), as a resource location, or null. */
    fun model(id: String): String? = try {
        dev.lone.itemsadder.api.ItemsAdder.Advanced.getItemModelResourceLocation(id)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }

    /** The character of the font image [id] (`namespace:id`), without the colour codes ItemsAdder wraps it in. */
    fun glyph(id: String): String? = try {
        val image = dev.lone.itemsadder.api.FontImages.FontImageWrapper(id)
        if (image.exists()) image.string.replace(Regex("§."), "").takeIf { it.isNotEmpty() } else null
    } catch (e: Throwable) {
        null
    }
}

/** Nexo's items, as `nexo:<id>`. */
class NexoSource : ItemSource, ListsItems {
    override val prefix = "nexo"
    override fun item(id: String): ItemStack? = NexoItems.itemFromId(id)?.build()
    override fun ids(): List<String> = NexoItems.itemNames().sorted()
}

/** HeadDatabase's heads, as `hdb:<id>`. */
class HeadDatabaseSource : ItemSource {
    override val prefix = "hdb"
    private val api = HeadDatabaseAPI()
    override fun item(id: String): ItemStack? = api.getItemHead(id)

    /** The texture HeadDatabase holds for [id], for a `head:`/`skull` that names `hdb:<id>`; null when it has none. */
    fun texture(id: String): String? = runCatching { if (api.isHead(id)) api.getBase64(id) else null }.getOrNull()
}

/** The address Nexo sends its resource pack from (an S3 or LobFile address, a Polymath or self-hosted one), as the players get it. */
object NexoPackHost {
    /** The address, or null if Nexo has none yet or its API is not the one this was written for. */
    fun url(): String? = try {
        com.nexomc.nexo.NexoPlugin.instance().packServer().packUrl().takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }
}
