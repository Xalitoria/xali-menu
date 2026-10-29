package mc.xalitoria.menu.paper.api

import mc.xalitoria.menu.api.ActionType
import mc.xalitoria.menu.api.ConditionType
import mc.xalitoria.menu.api.ItemSource
import mc.xalitoria.menu.api.Variables
import mc.xalitoria.menu.api.XaliMenuApi
import mc.xalitoria.menu.paper.XaliMenu
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

    override fun registerItemSource(owner: Plugin, source: ItemSource) = plugin.registry.addSource(owner, source)
}
