package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.player.AsyncChatEvent
import mc.xalitoria.menu.api.model.Activator
import mc.xalitoria.menu.api.model.BlockClick
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.util.message.PLAIN
import mc.xalitoria.util.message.toComponent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.entity.Player

import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * Opens menus for what the players do: the `open-with` of the menus. The events of a player come on the thread of the
 * region they are in, where a gate and the opening are done at once. The chat is the one that doesn't, and only has its
 * message looked at there.
 */
class ActivatorListener(private val plugin: XaliMenu) : Listener {
    private val index: ActivatorIndex get() = plugin.menus.activators

    /** The name a text gives an item or an entity, without its colors, as it reads on the item. */
    private val wantedName: (String) -> String = { PLAIN.serialize(it.toComponent()) }

    private fun plain(component: Component?): String? = component?.let(PLAIN::serialize)

    /**
     * Opens the menu of [entry] if its gate holds. [values] are the activator's own (`message`, `block`...) and reach the
     * gate and the menu's arguments as `{name}`. @return whether the menu was opened (or is open already)
     */
    private fun <A : Activator> trigger(player: Player, entry: Entry<A>, values: Map<String, String>): Boolean {
        val ctx = Context(player, entry.menu, emptyMap(), plugin.actions).with(values)
        if (!entry.activator.require.passes(ctx, run = true)) return false
        // Standing on a plate must not open the menu again and again.
        if (plugin.menus.view(player)?.name != entry.menu) plugin.menus.open(player, entry.menu, entry.activator.args.map(ctx::parse))
        return true
    }

    /** Tries [entries] in order until one opens its menu; cancels [event] if that one asks for it. @return whether one did */
    private fun <A : Activator> first(player: Player, entries: List<Entry<A>>, event: Cancellable, values: (A) -> Map<String, String>, matches: (A) -> Boolean): Boolean {
        for (entry in entries) {
            if (!matches(entry.activator)) continue
            if (trigger(player, entry, values(entry.activator))) {
                if (entry.activator.cancel) event.isCancelled = true
                return true
            }
        }
        return false
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (index.isEmpty || event.hand != EquipmentSlot.HAND && event.action != Action.PHYSICAL) return
        val player = event.player
        val sneaking = player.isSneaking
        val block = event.clickedBlock
        val click = when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> BlockClick.LEFT
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> BlockClick.RIGHT
            Action.PHYSICAL -> BlockClick.PHYSICAL
        }

        if (block != null) {
            val type = block.type.key.key
            val opened = first(player, index.blocks, event, { values(block) }) {
                it.matches(type, click, block.world.name, block.x, block.y, block.z, sneaking) { tag -> inTag(tag, block.type) }
            }
            if (opened) return
        }

        if (click == BlockClick.PHYSICAL) return
        val item = event.item ?: return
        first(player, index.items, event, { mapOf("item" to item.type.key.key) }) {
            it.matches(item.type.key.key, plain(item.itemMeta?.displayName()), model(item), click == BlockClick.LEFT, sneaking, wantedName)
        }
    }

    private fun values(block: org.bukkit.block.Block) = mapOf(
        "block" to block.type.key.key, "world" to block.world.name, "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString(),
    )

    private fun model(item: ItemStack): Int? = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats()?.firstOrNull()?.toInt()

    private fun inTag(key: String, material: Material): Boolean {
        val tag = NamespacedKey.fromString(key)?.let { Bukkit.getTag(Tag.REGISTRY_BLOCKS, it, Material::class.java) }
        return tag?.isTagged(material) == true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (index.entities.isEmpty() || event.hand != EquipmentSlot.HAND) return
        val entity = event.rightClicked
        val type = entity.type.key.key
        val name = plain(entity.customName())
        first(event.player, index.entities, event, { mapOf("entity" to type, "entity_name" to name.orEmpty()) }) {
            it.matches(type, name, event.player.isSneaking, wantedName)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (index.swaps.isEmpty()) return
        first(event.player, index.swaps, event, { emptyMap() }) { it.matches(event.player.isSneaking) }
    }

    /**
     * A player clicked the NPC called [name] (with the id [id]); [left] says which click, null if it is not known. The event
     * may come on any thread, so only the NPC is looked at here and the gate is asked on the player's own thread.
     */
    fun onNpc(player: Player, event: Cancellable, name: String, id: String, left: Boolean?) {
        if (index.npcs.isEmpty()) return
        val sneaking = player.isSneaking
        val matching = index.npcs.filter { it.activator.matches(name, id, left, sneaking) }
        val first = matching.firstOrNull() ?: return
        if (first.activator.cancel) event.isCancelled = true
        player.scheduler.run(plugin, { for (entry in matching) if (trigger(player, entry, mapOf("npc" to name, "npc_id" to id))) break }, null)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        for (entry in index.joins) {
            player.scheduler.runDelayed(plugin, { if (player.isOnline) trigger(player, entry, emptyMap()) }, null, entry.activator.delay.toLong().coerceAtLeast(1))
        }
    }

    /** Said off the main thread: only the text is looked at here, the rest is done on the player's thread. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (index.chats.isEmpty()) return
        val message = PLAIN.serialize(event.message())
        val player = event.player
        for (entry in index.chats) {
            if (!entry.activator.matches(message)) continue
            if (entry.activator.cancel) event.isCancelled = true
            player.scheduler.run(plugin, { trigger(player, entry, mapOf("message" to message)) }, null)
            return
        }
    }
}
