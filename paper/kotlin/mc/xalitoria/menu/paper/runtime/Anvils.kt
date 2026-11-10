package mc.xalitoria.menu.paper.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import mc.xalitoria.menu.paper.XaliMenu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.view.AnvilView

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
        view.setItem(INPUT, named(prompt.initial))
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

    private fun named(text: String) = ItemStack(Material.PAPER).apply { editMeta { it.customName(Component.text(text)) } }

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
        if (plugin.inputs.submit(shown.prompt, reply) == Prompt.Verdict.INVALID) plugin.inputs.reject(shown.prompt)
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
        // What leaving it runs may open a window, which can't be done from the event of one closing.
        player.scheduler.run(plugin, { plugin.inputs.expire(shown.prompt) }, null)
    }

    private companion object {
        const val INPUT = 0
        const val SECOND = 1
        const val RESULT = 2
    }
}
