package mc.xalitoria.menu.paper.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import mc.xalitoria.menu.paper.XaliMenu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.view.AnvilView
import org.bukkit.persistence.PersistentDataType

/**
 * The text input of a client without dialogs: an anvil whose rename field is the text field. It is a window of its own, not
 * the player's inventory: the paper in it is only there to be renamed, nothing in it can be taken, and whatever is left in
 * it when it closes is cleared rather than given back, so nothing of it can reach the player.
 */
class Anvils(private val plugin: XaliMenu) : Listener {
    private class Open(val view: AnvilView, val prompt: Prompt)

    private val open = ConcurrentHashMap<UUID, Open>()

    /** Opens the anvil of [prompt] for its viewer. */
    fun show(prompt: Prompt) {
        val player = prompt.ctx.player
        val view = MenuType.ANVIL.builder().title(prompt.ctx.component(prompt.title)).build(player) ?: return
        open[player.uniqueId] = Open(view, prompt)
        view.setItem(INPUT, named(prompt.initial.take(prompt.maxLength)))
        view.maximumRepairCost = 0
        player.openInventory(view)
    }

    /** Closes the anvil [player] has open for a prompt, if any; nothing in it is kept. */
    fun close(player: Player) {
        val shown = open.remove(player.uniqueId) ?: return
        clear(shown.view)
        if (player.openInventory === shown.view) player.closeInventory()
    }

    private fun clear(view: AnvilView) {
        view.setItem(INPUT, null)
        view.setItem(SECOND, null)
        view.setItem(RESULT, null)
    }

    /** The paper the player renames. It is marked so that one that ever got out of the anvil can be taken away again. */
    private fun named(text: String) = ItemStack(Material.PAPER).apply {
        editMeta {
            it.customName(Component.text(text))
            it.persistentDataContainer.set(MARK, PersistentDataType.BYTE, 1)
        }
    }

    /** Takes the papers of an anvil that got into [player]'s inventory (a window cut off by the plugin or the server going away). */
    private fun sweep(player: Player) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val held = inventory.getItem(slot) ?: continue
            if (held.type == Material.PAPER && held.itemMeta?.persistentDataContainer?.has(MARK, PersistentDataType.BYTE) == true) inventory.setItem(slot, null)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = sweep(event.player)

    private fun of(view: InventoryView): Open? = (view.player as? Player)?.let { open[it.uniqueId] }?.takeIf { it.view === view }

    /** What the player typed: the anvil's result always shows it, so it can be taken however the item was named. */
    @EventHandler
    fun onPrepare(event: PrepareAnvilEvent) {
        val shown = of(event.view) ?: return
        event.result = named(shown.view.renameText.orEmpty())
        shown.view.repairCost = 0
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val shown = of(event.view) ?: return
        // Nothing is taken, put in or carried over, whichever inventory the click was in; only the result slot counts.
        event.isCancelled = true
        if (event.rawSlot != RESULT || event.clickedInventory !== event.view.topInventory) return
        val reply = shown.view.renameText.orEmpty()
        val verdict = plugin.inputs.submit(shown.prompt, reply)
        if (verdict.retry) plugin.inputs.reject(shown.prompt, verdict)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        if (of(event.view) != null) event.isCancelled = true
    }

    /** Leaving the anvil without taking the result cancels the prompt. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val shown = of(event.view) ?: return
        open.remove(player.uniqueId, shown)
        clear(shown.view)
        plugin.inputs.expire(shown.prompt, deferred = true)
    }

    private companion object {
        val MARK = NamespacedKey("xalimenu", "anvil_input")
        const val INPUT = 0
        const val SECOND = 1
        const val RESULT = 2
    }
}
