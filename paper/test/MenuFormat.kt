import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import net.xalitoria.menu.model.*
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
    fun aLongListOfNumbersIsFilledInLines() {
        val slots = (10..37).toList()
        val menu = Menu("T", items = mapOf("a" to Item("stone", slots = slots)))
        val text = Registry.format.encode(menu)
        assertTrue(text.lines().none { it.length > 100 })
        assertTrue(text.lines().size < 16, "not a line for each number:\n$text")
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

class MenuLayout {
    private val menu = Registry.format.decode(
        """
        title = "Hi"
        size = 27
        layout = ["#########", "#  o    #", "##### ###"]
        legend { "#" = border, o = ok }
        items {
          border { material = gray_stained_glass_pane, name = " " }
          ok { material = lime_wool, slot = 0 }
        }
        """.trimIndent()
    )

    @Test
    fun theLegendPlacesItemsWhereTheirCharactersStand() {
        val slots = menu.placed().getValue("border").slots
        assertEquals((0..8).toList() + listOf(9, 17) + (18..22).toList() + (24..26).toList(), slots)
    }

    @Test
    fun anItemKeepsItsOwnSlotsToo() {
        val ok = menu.placed().getValue("ok")
        assertEquals(0, ok.slot)
        assertEquals([12], ok.slots)
    }

    @Test
    fun aMenuWithoutALayoutIsLeftAlone() {
        val plain = Registry.format.decode("title = \"Hi\"\nitems.a { material = stone, slot = 3 }")
        assertTrue(plain.placed() === plain.items)
    }

    @Test
    fun badLayoutsAreRefused() {
        val long = runCatching { Menu(title = "x", layout = ["0123456789"]) }
        assertTrue(long.exceptionOrNull() is IllegalArgumentException)
        val word = runCatching { Menu(title = "x", legend = mapOf("ab" to "a"), items = mapOf("a" to Item(material = "stone"))) }
        assertTrue(word.exceptionOrNull() is IllegalArgumentException)
        val unknown = runCatching { Menu(title = "x", legend = mapOf("a" to "gone")) }
        assertTrue(unknown.exceptionOrNull() is IllegalArgumentException)
    }
}

class ExampleMenu {
    @Test
    fun theBundledPagedExampleReads() {
        val menu = Registry.format.decode(javaClass.getResource("/example-pages.conf")!!.readText())
        val pages = menu.pages!!
        assertEquals(mc.xalitoria.menu.paper.catalog.Catalogs.Players(), pages.catalog)
        assertEquals(28, pages.slots.size)
        assertTrue(pages.slots.all { it in 0 until menu.size }, "every slot is inside the menu")
        assertTrue(pages.slots.none { it in menu.items.values.flatMap { item -> listOfNotNull(item.slot) } }, "no button sits on a listed slot")
        assertEquals(setOf("prev", "info", "next"), menu.items.keys)
    }

    @Test
    fun theBundledExampleReads() {
        val menu = Registry.format.decode(javaClass.getResource("/example.conf")!!.readText())
        assertEquals(["xmenu-example"], menu.commands)
        assertEquals(5.seconds, menu.refresh)
        assertEquals(setOf("info", "vip", "novip", "close"), menu.items.keys)
        assertEquals(Conditions.Permission("xalimenu.example.vip"), menu.items.getValue("vip").view?.require?.single()?.condition)
    }
}
