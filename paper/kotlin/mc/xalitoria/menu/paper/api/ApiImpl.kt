package mc.xalitoria.menu.paper.api

import mc.xalitoria.menu.paper.XaliMenu
import net.xalitoria.menu.ActionType
import net.xalitoria.menu.ActivatorSource
import net.xalitoria.menu.ActivatorType
import net.xalitoria.menu.CatalogType
import net.xalitoria.menu.ConditionType
import net.xalitoria.menu.ItemPropertyType
import net.xalitoria.menu.ItemSource
import net.xalitoria.menu.Variables
import net.xalitoria.menu.XaliMenuApi
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** What other plugins get from [XaliMenuApi.get]. */
class ApiImpl(private val plugin: XaliMenu) : XaliMenuApi {
    override val variables: Variables get() = plugin.variables

    override val menus: Set<String> get() = plugin.menus.names.toSet()

    override fun open(player: Player, menu: String, args: List<String>) = plugin.menus.open(player, menu, args)

    override fun open(player: Player, menu: String) = open(player, menu, emptyList())

    override fun registerAction(owner: Plugin, name: String, type: ActionType) = plugin.registry.addAction(owner, name, type)

    override fun registerCondition(owner: Plugin, name: String, type: ConditionType) = plugin.registry.addCondition(owner, name, type)

    override fun registerCatalog(owner: Plugin, name: String, type: CatalogType) = registerCatalog(owner, name, emptyMap(), type)

    override fun registerCatalog(owner: Plugin, name: String, values: Map<String, String>, type: CatalogType) =
        plugin.registry.addCatalog(owner, name, type, values)

    override fun registerCatalog(owner: Plugin, name: String, values: Map<String, String>, settings: Map<String, String>, type: CatalogType) =
        plugin.registry.addCatalog(owner, name, type, values, settings)

    override fun registerActivator(owner: Plugin, name: String, type: ActivatorType): ActivatorSource {
        plugin.registry.addActivator(owner, name, type)
        return ActivatorSource { player, values -> plugin.activators.onAdded(name, player, values) }
    }

    override fun registerItemSource(owner: Plugin, source: ItemSource) = plugin.registry.addSource(owner, source)

    override fun registerItemProperty(owner: Plugin, name: String, type: ItemPropertyType) = registerItemProperty(owner, name, emptyMap(), type)

    override fun registerItemProperty(owner: Plugin, name: String, settings: Map<String, String>, type: ItemPropertyType) =
        plugin.registry.addItemProperty(owner, name, type, settings)
}
