import mc.xalitoria.menu.paper.runtime.slotted
import mc.xalitoria.menu.paper.screen.Overlay
import mc.xalitoria.menu.paper.screen.windowIndex
import net.xalitoria.menu.model.Menu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A menu that draws in the player's own inventory, and what happens to what was there. */
class PlayerInventory {
    @Test
    fun theInventoryKeyIsRead() {
        val menu = Registry.format.decode(
            """
            title = "Hi"
            size = 9
            inventory {
              back { material = arrow, slot = 8, name = "Back" }
              rows { material = stone, slots = [9, 10, 35] }
            }
            """.trimIndent(),
        )
        assertEquals(setOf("back", "rows"), menu.inventory.keys)
        assertEquals(8, menu.inventory.getValue("back").slot)
        assertEquals(listOf(9, 10, 35), menu.inventory.getValue("rows").slots)
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, emptyList())))
    }

    @Test
    fun aMenuWithoutOneDrawsNothingThere() {
        assertTrue(Registry.format.decode("title = \"Hi\"").inventory.isEmpty())
    }

    @Test
    fun itemsThatClaimASlotAreSortedLikeTheMenusOwn() {
        val menu = Registry.format.decode(
            """
            title = "Hi"
            inventory {
              b { material = stone, slot = 3, priority = 1 }
              a { material = dirt, slot = 3, priority = 2 }
              c { material = glass, slot = 4 }
            }
            """.trimIndent(),
        )
        val bySlot = slotted(menu.inventory)
        assertEquals(listOf("b", "a"), bySlot.getValue(3).map { it.first })
        assertEquals(setOf(3, 4), bySlot.keys)
        assertEquals(0..35, Menu.INVENTORY_SLOTS)
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
