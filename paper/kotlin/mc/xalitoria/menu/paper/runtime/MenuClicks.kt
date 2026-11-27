package mc.xalitoria.menu.paper.runtime

import net.xalitoria.menu.model.Clicks
import net.xalitoria.menu.model.Handler
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.math.BigDecimal

/**
 * Pure click-routing, resync and item-marking rules that harden menus against duplication, action replay and
 * legacy-client (ViaVersion < 1.17) desyncs. Kept server-free where possible so every rule has unit tests.
 */
object MenuClicks {
    /** Persistent-data marker stamped only onto stacks displayed inside a menu window or packet overlay, never onto real rewards. */
    val MENU_ITEM_MARK = NamespacedKey("xalimenu", "menu_item")

    /**
     * Click types that represent intentional button presses in the menu schema. Other gestures (`DOUBLE_CLICK`,
     * `NUMBER_KEY`, `DROP`, `CONTROL_DROP`, `SWAP_OFFHAND`, `CREATIVE`, border clicks, `UNKNOWN`) stay cancelled
     * and never trigger `click.any`, preventing double-click action replays and stray hotbar/drop activations.
     */
    val HANDLED_CLICKS: Set<ClickType> = setOf(
        ClickType.LEFT,
        ClickType.RIGHT,
        ClickType.SHIFT_LEFT,
        ClickType.SHIFT_RIGHT,
        ClickType.MIDDLE,
    )

    /** Click types that are prone to leaving client-side ghost items or stale cursor/offhand state after cancellation. */
    val RESYNC_CLICKS: Set<ClickType> = setOf(
        ClickType.NUMBER_KEY,
        ClickType.SWAP_OFFHAND,
        ClickType.DROP,
        ClickType.CONTROL_DROP,
        ClickType.DOUBLE_CLICK,
        ClickType.CREATIVE,
        ClickType.MIDDLE,
        ClickType.UNKNOWN,
    )

    /** Storage-only container type names with no vanilla recipe, crafting, repair or result-slot logic. */
    val SAFE_CONTAINER_TYPES: Set<String> = setOf(
        "CHEST",
        "HOPPER",
        "DISPENSER",
        "DROPPER",
        "SHULKER_BOX",
        "BARREL",
    )

    /** Whether [typeName] names a plain storage container without vanilla recipe or result-slot logic. */
    fun isSafeContainerType(typeName: String): Boolean = typeName.trim().uppercase() in SAFE_CONTAINER_TYPES

    /** Resolves the handler for [click] on [clicks], returning null for non-button click types so `any` cannot replay on double-click or stray keys. */
    fun resolveHandler(clicks: Clicks, click: ClickType): Handler? = when (click) {
        ClickType.LEFT -> clicks.left ?: clicks.any
        ClickType.RIGHT -> clicks.right ?: clicks.any
        ClickType.SHIFT_LEFT -> clicks.shiftLeft ?: clicks.any
        ClickType.SHIFT_RIGHT -> clicks.shiftRight ?: clicks.any
        ClickType.MIDDLE -> clicks.middle ?: clicks.any
        else -> null
    }

    /**
     * Whether a cancelled click should trigger a debounced next-tick `player.updateInventory()` resync:
     * always for legacy clients (< 1.17 via ViaVersion, whose click packets carry no changed-slots list),
     * always in creative mode, and for swap/drop/double-click/unknown gestures on any version.
     */
    fun needsResyncAfterCancel(click: ClickType, creative: Boolean, legacyClient: Boolean): Boolean =
        legacyClient || creative || click in RESYNC_CLICKS

    /** Parses [text] as a strictly positive whole number (> 0), or null if invalid, zero or negative. */
    fun positiveInt(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }

    /** Parses [text] as a strictly positive decimal (> 0), or null if invalid, zero or negative. */
    fun positiveDecimal(text: String): BigDecimal? = text.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    /** Parses [text] as a finite non-negative number (>= 0), or null if invalid, NaN, infinite or negative. */
    fun nonNegativeDouble(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    /** Marks [stack] as a menu display item so creative clones or leaked stacks are recognized and swept. */
    fun markDisplayItem(stack: ItemStack): ItemStack {
        if (!stack.isEmpty) {
            stack.editMeta { it.persistentDataContainer.set(MENU_ITEM_MARK, PersistentDataType.BYTE, 1) }
        }
        return stack
    }

    /** Whether [stack] carries the menu display marker. */
    fun isMenuDisplayItem(stack: ItemStack?): Boolean =
        stack != null && !stack.isEmpty && stack.hasItemMeta() && stack.itemMeta.persistentDataContainer.has(MENU_ITEM_MARK, PersistentDataType.BYTE)

    /** Removes any marked menu display stacks from [player]'s inventory, ender chest and cursor. @return how many slots were cleared */
    fun sweepDisplayItems(player: Player): Int {
        var removed = 0
        for (inventory in listOf(player.inventory, player.enderChest)) {
            for (slot in 0 until inventory.size) {
                if (isMenuDisplayItem(inventory.getItem(slot))) {
                    inventory.setItem(slot, null)
                    removed++
                }
            }
        }
        if (isMenuDisplayItem(player.itemOnCursor)) {
            player.setItemOnCursor(null)
            removed++
        }
        return removed
    }
}
