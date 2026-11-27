package fixture.ext.plain

import fixture.shared.Seen
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActivatorFilter
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.Condition
import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.ItemProperty
import net.xalitoria.menu.ItemSource
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.MenuExtension
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack

class Ping(val n: Int) : Action {
    override fun run(ctx: MenuContext) = Unit
}

object Yes : Condition {
    override fun test(ctx: MenuContext) = true
}

object Rows : Catalog {
    override fun elements(ctx: MenuContext): List<Map<String, String>> = emptyList()
}

object Source : ItemSource {
    override val prefix = "plainsource"
    override fun item(id: String): ItemStack? = null
}

class Hear : Listener

class Badge(val label: String) : ItemProperty {
    override fun apply(item: ItemStack, ctx: MenuContext) = Unit
}

class PlainExtension : MenuExtension() {
    override val identifier = "plain"
    override val author = "tests"
    override val version = "1.2.3"

    override fun register(context: ExtensionContext) {
        context.registerAction("ping") { Ping(it.int("n", 1)) }
        context.registerCondition("yes") { Yes }
        context.registerCatalog("rows", mapOf("name" to "x"), mapOf("world" to "w")) { Rows }
        context.registerItemSource(Source)
        context.registerItemProperty("badge", mapOf("label" to "vip")) { Badge(it.string("label", "vip")) }
        context.registerCompleter("things") { listOf("alpha", "beta") }
        context.registerListener(Hear())
        Seen.activator = context.registerActivator("emote") { ActivatorFilter { true } }
        Seen.log += "plain registered with data in ${context.dataFolder.name}"
    }

    override fun unregister() {
        Seen.log += "plain unregistered"
    }
}
