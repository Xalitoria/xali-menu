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
}
