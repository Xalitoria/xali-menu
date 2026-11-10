import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import net.xalitoria.menu.model.Accept
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import mc.xalitoria.menu.paper.action.Actions

/** The slots that take an item from the player: how they are written. What is taken is checked live (`live/bot/deposit.js`). */
class Deposits {
    @Test
    fun anAcceptIsReadAndWrittenBack() {
        val menu = Registry.format.decode(
            """
            title = "Bank"
            size = 27
            items {
              slot {
                material = air, slot = 13
                accept {
                  material = diamond, amount = 2, name = "Shiny", model = 4
                  then = [ { type = message, text = "Deposited {amount} {item}" } ]
                  reject = [ { type = message, text = "Not that" } ]
                }
              }
            }
            """.trimIndent(),
        )
        val accept = menu.items.getValue("slot").accept!!
        assertEquals("diamond", accept.material)
        assertEquals(2, accept.amount)
        assertEquals("Shiny", accept.name)
        assertEquals(4, accept.model)
        assertEquals(1, accept.then.size)
        assertEquals(1, accept.reject.size)
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, emptyList())))
    }

    @Test
    fun anItemTakesNothingUnlessItSaysSo() {
        assertNull(Item("stone", slot = 0).accept)
    }

    @Test
    fun anAcceptNeedsActionsAndASaneAmount() {
        assertFailsWith<IllegalArgumentException> { Accept(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Accept(listOf(Actions.Close), amount = 0) }
        assertFailsWith<IllegalArgumentException> { Accept(listOf(Actions.Close), amount = 100) }
        assertEquals(1, Accept(listOf(Actions.Close)).amount)
    }

    @Test
    fun aMenuAndItsItemsAreEqualWithTheSameAccept() {
        val item = Item("air", slot = 4, accept = Accept(listOf(Actions.Close)))
        assertEquals(Menu("T", items = mapOf("a" to item)), Menu("T", items = mapOf("a" to item.copy())))
    }
}
