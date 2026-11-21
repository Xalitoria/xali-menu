import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.format.MenuFormatException
import mc.xalitoria.menu.paper.runtime.slotted
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Matrix
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.MenuMatrix
import net.xalitoria.menu.model.Pages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Menu-global `matrix { cells, map }` and paged `pages.matrix { cells, templates }`. */
class MatrixSlots {
    private val format = MenuFormat(TypeRegistry().apply {
        Actions.register(this)
        Conditions.register(this)
        Catalogs.register(this)
    }.module)

    private val stone = Item(material = "stone")
    private val paper = Item(material = "paper")

    @Test
    fun aMatrixReadsRowsLeftToRightAndTopToBottom() {
        val matrix = Matrix(["#########", "#xxx_xxy#", "####c"], mapOf("x" to stone, "y" to paper, "c" to Item(material = "barrier")))
        assertEquals([10, 11, 12, 14, 15, 16, 22], matrix.cells().keys.toList())
        assertSame(paper, matrix.cells().getValue(16))
        assertEquals("barrier", matrix.cells().getValue(22).material)
    }

    @Test
    fun aMatrixIsRefusedWhenItHasNothingToDraw() {
        assertFailsWith<IllegalArgumentException> { Matrix(["xx"], emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Matrix(["xx"], mapOf("xx" to stone)) }
        assertFailsWith<IllegalArgumentException> { Matrix(["xxxxxxxxxx"], mapOf("x" to stone)) }
        assertFailsWith<IllegalArgumentException> { Matrix(["___"], mapOf("x" to stone)) }
    }

    @Test
    fun pagesTakeOneOfSlotsLayoutOrMatrix() {
        val matrix = Matrix(["xy"], mapOf("x" to stone, "y" to paper))
        val pages = Pages(Catalogs.Worlds, matrix = matrix)
        assertEquals([0, 1], pages.listed())
        assertSame(stone, pages.templates().getValue(0))
        assertSame(paper, pages.templates().getValue(1))

        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, matrix = matrix, item = stone) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, matrix = matrix, slots = [1]) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, matrix = matrix, layout = listOf("x")) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds) }
    }

    @Test
    fun theOneItemOfPagesIsTheTemplateOfEverySlot() {
        val pages = Pages(Catalogs.Worlds, item = stone, layout = listOf("#xx"))
        assertEquals(mapOf(1 to stone, 2 to stone), pages.templates())
        assertSame(stone, pages.templates().getValue(1))
    }

    @Test
    fun aMenuMatrixPlacesAllItemsGloballyWithoutPerItemConflicts() {
        val border = Item(material = "gray_stained_glass_pane")
        val menu = Menu(
            title = "Framed",
            size = 27,
            matrix = MenuMatrix(
                cells = ["#########", "#   o   #", "#########"],
                map = mapOf("#" to "border", "o" to "ok"),
            ),
            items = mapOf("border" to border, "ok" to stone),
        )
        val slots = slotted(menu.placed())
        assertEquals((0..8).toList() + listOf(9, 13, 17) + (18..26).toList(), slots.keys.sorted())
        assertEquals(["border"], slots.getValue(0).map { it.first })
        assertEquals(["ok"], slots.getValue(13).map { it.first })
    }

    @Test
    fun aMenuFileWritesGlobalMatrixAndPagedMatrix() {
        val source = """
            title = "&8Players ({page}/{pages})"
            size = 54
            matrix {
                cells = [
                    "#########"
                    "#       #"
                ]
                map { "#" = frame }
            }
            pages {
                catalog { type = "worlds" }
                matrix {
                    cells = [
                        "#########"
                        "#xxxxxxx#"
                        "#xxxxxxx#"
                        "#yyyyyyy#"
                        "#########"
                        "####c####"
                    ]
                    templates {
                        x { material = "paper", name = "&e{name}" }
                        y { material = "map", name = "&7{name}" }
                        c { material = "barrier", name = "Close", click.any.actions = [{ type = "close" }] }
                    }
                }
            }
            items {
                frame { material = "gray_stained_glass_pane", name = " " }
            }
        """.trimIndent()
        val read = format.decodeChecked(source)
        assertEquals(emptyList<String>(), read.warnings.map { it.toString() })
        val pages = assertNotNull(read.menu.pages)
        assertEquals(22, pages.listed().size)
        assertEquals("paper", pages.templates().getValue(10).material)
        assertEquals("map", pages.templates().getValue(28).material)
        assertEquals("barrier", pages.templates().getValue(49).material)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17), read.menu.placed().getValue("frame").claimed())

        val again = format.decodeChecked(format.encode(read.menu))
        assertEquals(emptyList<String>(), again.warnings.map { it.toString() })
        assertEquals(read.menu, again.menu)
    }

    @Test
    fun aMatrixFileThatBreaksTheRulesIsToldWhere() {
        val bad = """
            title = "x"
            size = 54
            pages {
                catalog { type = "worlds" }
                matrix { cells = ["xx"], templates { "xy" { material = "stone" } } }
            }
        """.trimIndent()
        val failure = assertFailsWith<Exception> { format.decodeChecked(bad) }
        assertTrue(failure is MenuFormatException || failure is IllegalArgumentException || failure.cause is IllegalArgumentException, failure.toString())
    }
}
