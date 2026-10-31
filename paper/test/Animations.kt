import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.model.Animation
import mc.xalitoria.menu.api.model.Frame
import mc.xalitoria.menu.api.model.Item
import mc.xalitoria.menu.api.model.Menu
import mc.xalitoria.menu.api.model.Pages
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.FrameCursor
import mc.xalitoria.menu.paper.runtime.FrameCursor.Step
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.slotted
import org.bukkit.entity.Player

class Animations {
    private fun steps(cursor: FrameCursor, count: Int) = List(count) { cursor.advance() }

    @Test
    fun anAnimationEndsAfterItsLastFrame() {
        val cursor = FrameCursor(3, loop = false)
        assertEquals(0, cursor.index)
        assertEquals(listOf(Step.NEXT, Step.NEXT, Step.ENDED), steps(cursor, 3))
        assertEquals(2, cursor.index, "it stays on the last frame")
    }

    @Test
    fun aLoopWrapsToTheFirstFrame() {
        val cursor = FrameCursor(2, loop = true)
        assertEquals(listOf(Step.NEXT, Step.WRAPPED, Step.NEXT, Step.WRAPPED), steps(cursor, 4))
        assertEquals(0, cursor.index)
        assertEquals(Step.WRAPPED, FrameCursor(1, loop = true).advance(), "a single frame loops on itself")
        assertEquals(Step.ENDED, FrameCursor(1, loop = false).advance())
    }

    @Test
    fun framesAreRead() {
        val menu = Registry.format.decode(
            """
            title = "Spin"
            size = 9
            animation {
              loop = true
              on-start = [{ type = message, text = "go" }]
              on-end = [{ type = message, text = "done" }]
              frames = [
                { delay = 500ms, items.a { material = stone, slot = 0 } },
                { delay = 1s, clear = false, items.b { material = dirt, slots = [1, 2] }, on-start = [{ type = close }] },
                { items {} }
              ]
            }
            """.trimIndent()
        )
        val animation = menu.animation!!
        assertEquals(true, animation.loop)
        assertEquals([Actions.Message("go")], animation.onStart)
        assertEquals(3, animation.frames.size)
        assertEquals(500.milliseconds, animation.frames[0].delay)
        assertEquals(true, animation.frames[0].clear)
        assertEquals(1.seconds, animation.frames[1].delay)
        assertEquals(false, animation.frames[1].clear)
        assertEquals([Actions.Close], animation.frames[1].onStart)
        assertEquals(1.seconds, animation.frames[2].delay, "a second unless said otherwise")
        assertEquals([1, 2], animation.frames[1].items.getValue("b").slots)
    }

    @Test
    fun anAnimationIsWrittenAndReadBack() {
        val menu = Menu(
            title = "x",
            animation = Animation(
                frames = [Frame(delay = 250.milliseconds, items = mapOf("a" to Item(material = "stone", slot = 3))), Frame(clear = false)],
                loop = true,
                onEnd = [Actions.Close],
            ),
        )
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu)))
    }

    @Test
    fun anAnimationNeedsAFrame() {
        assertFailsWith<IllegalArgumentException> { Animation(frames = []) }
        assertFailsWith<Exception> { Registry.format.decode("title = x\nanimation.frames = []") }
    }

    @Test
    fun itemsOfAFrameAreSortedIntoSlotsByPriority() {
        val slots = slotted(
            mapOf(
                "late" to Item(material = "stone", slot = 1, priority = 5),
                "early" to Item(material = "dirt", slots = [1, 2], priority = 0),
            ),
        )
        assertEquals(listOf("early", "late"), slots.getValue(1).map { it.first })
        assertEquals(listOf("early"), slots.getValue(2).map { it.first })
    }

    // Pages: the layout.

    private val item = Item(material = "stone")

    @Test
    fun aLayoutMarksTheSlotsOfTheElements() {
        val pages = Pages(Catalogs.Worlds, item = item, layout = listOf("#########", "#xxxxxxx#", "#xxxxxxx#", "####x####"))
        assertEquals((10..16).toList() + (19..25).toList() + 31, pages.listed())
        assertEquals(15, pages.listed().size)
    }

    @Test
    fun aLayoutMayUseAnotherCellAndShortRows() {
        val pages = Pages(Catalogs.Worlds, item = item, layout = listOf("..o", "", "o"), cell = "o")
        assertEquals([2, 18], pages.listed())
    }

    @Test
    fun slotsAreUsedAsTheyAre() {
        assertEquals([4, 2, 3], Pages(Catalogs.Worlds, item = item, slots = [4, 2, 3]).listed())
    }

    @Test
    fun slotsAndLayoutAreNotMixed() {
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, item = item, slots = [1], layout = listOf("x")) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, item = item) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, item = item, layout = listOf("#########")) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, item = item, layout = listOf("xxxxxxxxxx")) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, item = item, layout = listOf("x"), cell = "xx") }
    }

    @Test
    fun aLayoutIsRead() {
        val menu = Registry.format.decode(
            """
            title = x
            pages {
              catalog { type = worlds }
              layout = ["#########", "#xxxxxxx#"]
              item { material = stone }
            }
            """.trimIndent()
        )
        assertEquals((10..16).toList(), menu.pages!!.listed())
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu)))
    }

    // Nested menus: an element of a catalog opens another menu with its values.

    @Test
    fun anElementOpensAnotherMenuWithItsValues() {
        val opened = mutableListOf<Pair<String, List<String>>>()
        val host = object : Host {
            override fun run(actions: List<Action>, ctx: Context) = Unit
            override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
            override fun console(command: String) = Unit
            override fun open(player: Player, menu: String, args: List<String>) {
                opened += menu to args
            }
            override fun close(player: Player) = Unit
            override fun refresh(player: Player) = Unit
            override fun page(player: Player, relative: Boolean, value: Int) = Unit
        }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player
        val element = Context(player, "list", emptyMap(), host).with(mapOf("name" to "Alex", "index" to "3"))
        Actions.Open("profile", ["{name}", "{index}"]).run(element)
        assertEquals(listOf("profile" to listOf("Alex", "3")), opened)
    }
}
