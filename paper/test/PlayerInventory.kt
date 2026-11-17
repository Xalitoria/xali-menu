import mc.xalitoria.menu.paper.runtime.slotted
import mc.xalitoria.menu.paper.runtime.slottedInventory
import mc.xalitoria.menu.paper.screen.Overlay
import mc.xalitoria.menu.paper.screen.windowIndex
import net.xalitoria.menu.model.Menu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A menu that draws in the player's own inventory, and what happens to what was there. */
class PlayerInventory {
    @Test
    fun theInventorySlotsOfAnItemAreRead() {
        val menu = Registry.format.decode(
            """
            title = "Hi"
            size = 9
            items {
              back { material = arrow, inventory-slots = [8], name = "Back" }
              rows { material = stone, slot = 0, inventory-slots = [9, 10, 35] }
            }
            """.trimIndent(),
        )
        assertEquals(listOf(8), menu.items.getValue("back").inventorySlots)
        assertEquals(listOf(9, 10, 35), menu.items.getValue("rows").inventorySlots)
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, emptyList())))
    }

    @Test
    fun aMenuWithoutOneDrawsNothingThere() {
        val menu = Registry.format.decode("title = \"Hi\"\nitems.a { material = stone, slot = 0 }")
        assertTrue(slottedInventory(menu).isEmpty())
    }

    @Test
    fun itemsThatClaimASlotAreSortedLikeTheMenusOwn() {
        val menu = Registry.format.decode(
            """
            title = "Hi"
            items {
              b { material = stone, inventory-slots = [3], priority = 1 }
              a { material = dirt, inventory-slots = [3], priority = 2 }
              c { material = glass, inventory-slots = [4] }
            }
            """.trimIndent(),
        )
        val bySlot = slottedInventory(menu)
        assertEquals(listOf("b", "a"), bySlot.getValue(3).map { it.first })
        assertEquals(setOf(3, 4), bySlot.keys)
        assertEquals(0..35, Menu.INVENTORY_SLOTS)
    }

    @Test
    fun anItemOfTheWindowIsDrawnInTheInventoryToo() {
        val menu = Registry.format.decode(
            """
            title = "Hi"
            size = 9
            items {
              filler { material = gray_stained_glass_pane, slots = [0, 1], inventory-slots = [9, 10, 0], priority = 5 }
              coins { material = gold_ingot, inventory-slots = [9] }
            }
            """.trimIndent(),
        )
        val bySlot = slottedInventory(menu)
        assertEquals(setOf(0, 9, 10), bySlot.keys)
        // both claim slot 9: equal priority goes by name
        assertEquals(listOf("coins", "filler"), bySlot.getValue(9).map { it.first })
        // the window keeps its own slots, unchanged by the inventory ones
        assertEquals(setOf(0, 1), slotted(menu.items).keys)
    }

    @Test
    fun theRowsOfTheInventoryComeAfterTheSlotsOfTheWindow() {
        // a chest of 27: the rows above the hotbar are windows 27 to 53, the hotbar 54 to 62
        assertEquals(27, windowIndex(27, 9))
        assertEquals(53, windowIndex(27, 35))
        assertEquals(54, windowIndex(27, 0))
        assertEquals(62, windowIndex(27, 8))
        // a hopper of 5
        assertEquals(5, windowIndex(5, 9))
        assertEquals(5 + 27 + 4, windowIndex(5, 4))
        assertEquals((0..35).map { windowIndex(9, it) }.toSet().size, 36)
    }

    @Test
    fun anOverlayReplacesOnlyTheSlotsItDrawsIn() {
        val overlay = Overlay(9)
        overlay.packed = mapOf(windowIndex(9, 4) to "compass", windowIndex(9, 9) to "coins")
        val contents = MutableList(45) { "real$it" }
        overlay.rewrite(contents)
        assertEquals("compass", contents[windowIndex(9, 4)])
        assertEquals("coins", contents[9])
        assertEquals(43, contents.count { it.startsWith("real") })
        assertEquals("compass", overlay.replacement<String>(windowIndex(9, 4)))
        assertEquals(null, overlay.replacement<String>(0))
    }

    @Test
    fun aListShorterThanTheOverlayIsLeftAlone() {
        val overlay = Overlay(9)
        overlay.packed = mapOf(100 to "far")
        val contents = MutableList(45) { "real$it" }
        overlay.rewrite(contents)
        assertTrue(contents.all { it.startsWith("real") })
    }
}
