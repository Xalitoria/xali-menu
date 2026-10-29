import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import mc.xalitoria.menu.api.model.*
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.condition.Conditions

class MenuFormat {
    private val menu = Menu(
        title = "&8Jobs",
        size = 27,
        commands = ["jobs", "jobs browse"],
        refresh = 5.seconds,
        open = Gate([Check(Conditions.Permission("jobs.open"), deny = [Actions.Message("&cNo")])]),
        items = mapOf(
            "miner" to Item(
                material = "diamond_pickaxe",
                slot = 10,
                name = "&aMiner",
                lore = ["&7Mine ores", "&7A line that is long enough to push the whole list past the width of one line"],
                enchants = mapOf("sharpness" to 2),
                view = Gate([Check(Conditions.Not(Conditions.Money("100")))]),
                click = Clicks(
                    left = Handler([Actions.Player("jobs join miner"), Actions.Delay(20, Actions.Close)]),
                    shiftRight = Handler([Actions.Sound("entity.player.levelup", scope = Actions.Sound.Scope.WORLD)]),
                ),
            ),
        ),
    )

    @Test
    fun roundTrip() {
        val text = Registry.format.encode(menu, ["Imported"])
        assertTrue(text.startsWith("# Imported\n"))
        assertEquals(menu, Registry.format.decode(text))
    }

    @Test
    fun readsWhatPeopleWrite() {
        val read = Registry.format.decode(
            """
            title = "Hi"
            refresh = 1m 30s
            items.a {
              material = stone
              slot = 0
              click.any.actions = [ { type = close }, { type = open, menu = other } ]
            }
            """.trimIndent()
        )
        assertEquals(54, read.size)
        assertEquals(90.seconds, read.refresh)
        assertEquals([Actions.Close, Actions.Open("other")], read.items.getValue("a").click.any?.actions)
    }
}

class ExampleMenu {
    @Test
    fun theBundledExampleReads() {
        val menu = Registry.format.decode(javaClass.getResource("/example.conf")!!.readText())
        assertEquals(["xmenu-example"], menu.commands)
        assertEquals(5.seconds, menu.refresh)
        assertEquals(setOf("info", "vip", "novip", "close"), menu.items.keys)
        assertEquals(Conditions.Permission("xalimenu.example.vip"), menu.items.getValue("vip").view?.require?.single()?.condition)
    }
}
