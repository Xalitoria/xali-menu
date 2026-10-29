package mc.xalitoria.menu.paper.hook

import com.nexomc.nexo.api.NexoItems
import dev.lone.itemsadder.api.CustomStack
import me.arcaniax.hdb.api.HeadDatabaseAPI
import mc.xalitoria.menu.api.ItemSource
import org.bukkit.inventory.ItemStack

/** ItemsAdder's items, as `itemsadder:<namespace>:<id>`. */
class ItemsAdderSource : ItemSource {
    override val prefix = "itemsadder"
    override fun item(id: String): ItemStack? = CustomStack.getInstance(id)?.itemStack
}

/** Nexo's items, as `nexo:<id>`. */
class NexoSource : ItemSource {
    override val prefix = "nexo"
    override fun item(id: String): ItemStack? = NexoItems.itemFromId(id)?.build()
}

/** HeadDatabase's heads, as `hdb:<id>`. */
class HeadDatabaseSource : ItemSource {
    override val prefix = "hdb"
    private val api = HeadDatabaseAPI()
    override fun item(id: String): ItemStack? = api.getItemHead(id)
}
