import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mc.xalitoria.menu.paper.runtime.settle
import mc.xalitoria.menu.paper.runtime.slotted
import net.xalitoria.menu.model.Menu

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
    fun aSlotThatStillHoldsTheMenusItemGetsItsOriginalBack() {
        val plan = settle(mapOf(1 to "sword", 2 to null), { "ghost" }, { it == "ghost" })
        assertEquals(mapOf(1 to "sword", 2 to null), plan.restore)
        assertTrue(plan.give.isEmpty())
    }

    @Test
    fun aSlotEmptiedByTheMenuGetsItsOriginalBack() {
        val plan = settle(mapOf(1 to "sword"), { null }, { it == "ghost" })
        assertEquals(mapOf(1 to "sword"), plan.restore)
    }

    @Test
    fun aSlotSomeoneFilledKeepsItAndTheOriginalIsGiven() {
        val plan = settle(mapOf(1 to "sword", 2 to "bow"), { if (it == 1) "apple" else "ghost" }, { it == "ghost" })
        assertEquals(mapOf(2 to "bow"), plan.restore)
        assertEquals(listOf("sword"), plan.give)
    }

    @Test
    fun aSlotLeftAloneIsNotTouched() {
        val plan = settle(mapOf(1 to "sword"), { "sword" }, { it == "ghost" })
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.give.isEmpty())
    }
}
