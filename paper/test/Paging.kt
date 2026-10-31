import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.Catalog
import mc.xalitoria.menu.api.CatalogType
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.model.Pages
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.PageState
import mc.xalitoria.menu.paper.runtime.Placeholders
import mc.xalitoria.menu.paper.runtime.layoutPage
import mc.xalitoria.menu.paper.runtime.numbered
import mc.xalitoria.menu.paper.runtime.sortElements
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class Paging {
    private fun elements(vararg values: String) = values.map { mapOf("name" to it) }

    @Test
    fun pagesAreCountedFromTheTotal() {
        assertEquals(1, PageState(0, 7, 0).pages, "an empty list still has a page")
        assertEquals(1, PageState(7, 7, 0).pages)
        assertEquals(2, PageState(8, 7, 0).pages)
        assertEquals(3, PageState(15, 7, 0).pages)
    }

    @Test
    fun aRequestedPageThatDoesNotExistBecomesTheNearest() {
        assertEquals(0, PageState(10, 5, -3).page)
        assertEquals(1, PageState(10, 5, 9).page)
        assertEquals(0, PageState(0, 5, 4).page)
        assertEquals(5, PageState(10, 5, 1).from)
    }

    @Test
    fun textSeesPagesFromOne() {
        assertEquals(
            mapOf("page" to "2", "pages" to "3", "total" to "12", "page_next" to "3", "page_prev" to "1"),
            PageState(12, 5, 1).values(),
        )
        val first = PageState(12, 5, 0).values()
        assertEquals("1", first["page_prev"], "stays on the first page at the start")
        val last = PageState(12, 5, 2).values()
        assertEquals("3", last["page_next"], "stays on the last page at the end")
    }

    @Test
    fun sortingIsByNumberWhenBothAreNumbers() {
        val list = elements("10", "9", "100")
        assertEquals(listOf("9", "10", "100"), sortElements(list, "name", false).map { it["name"] })
        assertEquals(listOf("100", "10", "9"), sortElements(list, "name", true).map { it["name"] })
    }

    @Test
    fun sortingByTextIgnoresCaseAndKeepsTheOrderOfEqualOnes() {
        val list = listOf(mapOf("n" to "bob", "id" to "1"), mapOf("n" to "Alice", "id" to "2"), mapOf("n" to "BOB", "id" to "3"))
        assertEquals(listOf("2", "1", "3"), sortElements(list, "n", false).map { it["id"] })
        assertEquals(listOf("1", "3", "2"), sortElements(list, "n", true).map { it["id"] })
    }

    @Test
    fun anElementWithoutTheValueStaysLast() {
        val list = listOf(mapOf("id" to "x"), mapOf("n" to "b"), mapOf("n" to "a"))
        assertEquals(listOf("a", "b", null), sortElements(list, "n", false).map { it["n"] })
        assertEquals(listOf("b", "a", null), sortElements(list, "n", true).map { it["n"] })
        assertEquals(list, sortElements(list, null, false))
    }

    @Test
    fun elementsAreNumberedFromOne() {
        assertEquals(listOf("1", "2"), numbered(elements("a", "b")).map { it["index"] })
    }

    private fun numbers(count: Int) = (1..count).map { mapOf("value" to it.toString()) }

    @Test
    fun unlistedElementsAreDroppedBeforeThePagesAreCut() {
        // Of 1..10 the odd ones are listed: 5 elements, 2 to a page. A gate applied after paging would leave short pages.
        val layout = layoutPage(numbers(10), [10, 11], null, false, 0) { it.getValue("value").toInt() % 2 == 1 }
        assertEquals(3, layout.state.pages)
        assertEquals(5, layout.state.total)
        assertEquals(listOf(10 to "1", 11 to "3"), layout.placed.map { it.first to it.second["value"] })
        val last = layoutPage(numbers(10), [10, 11], null, false, 2) { it.getValue("value").toInt() % 2 == 1 }
        assertEquals(listOf(10 to "9"), last.placed.map { it.first to it.second["value"] })
    }

    @Test
    fun theIndexIsThePlaceInTheListedElements() {
        val layout = layoutPage(numbers(6), [0, 1], "value", true, 1) { it.getValue("value").toInt() != 5 }
        // Sorted 6 4 3 2 1 (5 is not listed), so the second page holds 3 and 2, at places 3 and 4.
        assertEquals(listOf("3" to "3", "4" to "2"), layout.placed.map { it.second.getValue("index") to it.second.getValue("value") })
    }

    @Test
    fun aPageBeyondTheEndIsTheLastOne() {
        val layout = layoutPage(numbers(5), [0, 1], null, false, 99) { true }
        assertEquals(2, layout.state.page)
        assertEquals(listOf("5"), layout.placed.map { it.second["value"] })
    }

    @Test
    fun nothingListedIsEmpty() {
        val layout = layoutPage(numbers(3), [0, 1], null, false, 4) { false }
        assertTrue(layout.empty)
        assertEquals(0, layout.state.page)
        assertEquals("1", layout.state.values()["pages"])
        assertTrue(layoutPage(emptyList(), [0], null, false, 0) { true }.empty)
    }

    // The catalogs, against a context that answers placeholders from a table.

    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) = Unit
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
        override fun page(player: Player, relative: Boolean, value: Int) = Unit
    }

    private fun ctx(vararg placeholders: Pair<String, String>, args: Map<String, String> = emptyMap()): Context {
        val values = mapOf(*placeholders)
        Placeholders.resolver = { _, text -> values.entries.fold(text) { acc, entry -> acc.replace(entry.key, entry.value) } }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player
        return Context(player, "test", args, host)
    }

    @Test
    fun valuesListsEntriesAndPiecesOfText() {
        val ctx = ctx("%list%" to "red; green ;;blue")
        val catalog = Catalogs.Values(list = ["x"], text = "%list%", separator = ";")
        assertEquals(listOf("x", "red", "green", "blue"), catalog.elements(ctx).map { it["value"] })
        assertEquals(listOf("red", " green ", "blue"), Catalogs.Values(text = "%list%", separator = ";", trim = false).elements(ctx).map { it["value"] })
    }

    @Test
    fun theSeparatorOfValuesIsNotAPattern() {
        assertEquals(listOf("a", "b"), Catalogs.Values(text = "a.b", separator = ".").elements(ctx()).map { it["value"] })
        assertFailsWith<IllegalArgumentException> { Catalogs.Values(separator = "") }
    }

    @Test
    fun rangeIncludesBothEnds() {
        assertEquals(listOf("1", "2", "3"), Catalogs.Range("1", "3").elements(ctx()).map { it["value"] })
        assertEquals(listOf("1", "3", "5"), Catalogs.Range("1", "5", step = 2).elements(ctx()).map { it["value"] })
        assertEquals(listOf("3", "2", "1"), Catalogs.Range("1", "3", descending = true).elements(ctx()).map { it["value"] })
        assertEquals(listOf("4", "5"), Catalogs.Range("%from%", "5").elements(ctx("%from%" to "4")).map { it["value"] })
        assertEquals(emptyList(), Catalogs.Range("5", "1").elements(ctx()))
        assertEquals(emptyList(), Catalogs.Range("x", "1").elements(ctx()), "a start that isn't a number gives nothing")
        assertFailsWith<IllegalArgumentException> { Catalogs.Range("1", "2", step = 0) }
    }

    @Test
    fun rangeIsLimited() {
        assertEquals(Catalogs.LIMIT, Catalogs.Range("1", "999999999").elements(ctx()).size)
    }

    @Test
    fun theElementIsInScopeForText() {
        val base = ctx(args = mapOf("who" to "Steve"))
        val element = base.with(mapOf("name" to "Alex", "who" to "Alex"))
        assertEquals("Alex and Alex", element.parse("{name} and {who}"), "the more local value counts")
        assertEquals("Steve", base.parse("{who}"), "the menu's own context is untouched")
        assertEquals("{name}", base.parse("{name}"))
        assertEquals("AB", element.with(mapOf("name" to "B")).parse("A{name}"))
    }

    @Test
    fun anElementValueCannotStartAPlaceholder() {
        val element = ctx("%secret%" to "leaked").with(mapOf("name" to "%secret%\nx"))
        assertEquals("show secretx", element.parse("show {name}"), "the % and the line break are taken out, nothing is looked up")
    }

    @Test
    fun textWithAScopeIsNotKeptForReuse() {
        // The same text must give the value of each element, not whatever it was first asked with.
        val base = ctx()
        assertEquals("a", base.with(mapOf("name" to "a")).parse("{name}"))
        assertEquals("b", base.with(mapOf("name" to "b")).parse("{name}"))
    }

    @Test
    fun theContextAskedForCarriesTheActionsThePageNeeds() {
        val calls = mutableListOf<String>()
        val recording = object : Host by host {
            override fun page(player: Player, relative: Boolean, value: Int) {
                calls += "${if (relative) "by" else "to"} $value"
            }
        }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player
        val ctx: MenuContext = Context(player, "test", emptyMap(), recording)
        Actions.Page(by = 1).run(ctx)
        Actions.Page(by = -2).run(ctx)
        Actions.Page(to = "first").run(ctx)
        Actions.Page(to = "last").run(ctx)
        Actions.Page(to = "4").run(ctx)
        assertEquals(listOf("by 1", "by -2", "to 1", "to ${Int.MAX_VALUE}", "to 4"), calls)
        assertFailsWith<IllegalArgumentException> { Actions.Page(to = "soon").run(ctx) }
    }

    @Test
    fun pageNeedsExactlyOneOfByAndTo() {
        assertFailsWith<IllegalArgumentException> { Actions.Page() }
        assertFailsWith<IllegalArgumentException> { Actions.Page(by = 1, to = "last") }
    }

    // Reading the menu file.

    @Test
    fun aPagedMenuIsRead() {
        val menu = Registry.format.decode(
            """
            title = "Players"
            size = 54
            pages {
              catalog { type = players, include-self = false }
              slots = [10, 11, 12]
              sort = name
              descending = true
              item {
                material = "head:{name}"
                name = "&e{name}"
                view.require = [{ condition { type = permission, node = "see.{name}" } }]
                click.left.actions = [{ type = player, command = "msg {name} hi" }]
              }
              empty { material = barrier, name = "&cNobody" }
            }
            items.next {
              material = arrow
              slot = 53
              name = "Page {page} of {pages}"
              click.any.actions = [{ type = page, by = 1 }]
            }
            """.trimIndent()
        )
        val pages = menu.pages!!
        assertEquals(Catalogs.Players(includeSelf = false), pages.catalog)
        assertEquals([10, 11, 12], pages.slots)
        assertEquals("name", pages.sort)
        assertTrue(pages.descending)
        assertEquals("head:{name}", pages.item.material)
        assertEquals("barrier", pages.empty?.material)
        assertEquals([Actions.Page(by = 1)], menu.items.getValue("next").click.any?.actions)
    }

    @Test
    fun aPagedMenuIsWrittenAndReadBack() {
        val menu = mc.xalitoria.menu.api.model.Menu(
            title = "Numbers",
            pages = Pages(
                catalog = Catalogs.Range("1", "100", step = 3),
                slots = [0, 1, 2],
                item = mc.xalitoria.menu.api.model.Item(material = "stone", name = "{value}"),
                sort = "value",
            ),
        )
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu)))
    }

    @Test
    fun aPagedMenuNeedsSlotsWithoutRepeats() {
        val item = mc.xalitoria.menu.api.model.Item(material = "stone")
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, [], item) }
        assertFailsWith<IllegalArgumentException> { Pages(Catalogs.Worlds, [1, 1], item) }
        assertFailsWith<Exception> {
            Registry.format.decode("title = x\npages { catalog { type = worlds }, slots = [], item { material = stone } }")
        }
    }

    // Catalogs of other plugins.

    private val owner = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args[0]
            "hashCode" -> 1
            else -> null
        }
    } as Plugin

    private class Homes(val count: Int) : Catalog {
        override fun elements(ctx: MenuContext) = (1..count).map { mapOf("name" to "home$it") }
    }

    private val menu = """
        title = x
        pages { catalog { type = "test:homes", count = 3 }, slots = [0, 1], item { material = stone } }
    """.trimIndent()

    @Test
    fun aCatalogOfAnotherPluginIsReadFromMenuFiles() {
        val registry = TypeRegistry().also { Catalogs.register(it); Actions.register(it) }
        assertFailsWith<Exception> { MenuFormat(registry.module, registry.externalNames).decode(menu) }

        registry.addCatalog(owner, "test:homes", CatalogType { Homes(it.int("count", 1)) })
        assertEquals(setOf("test:homes"), registry.externalNames)
        val catalog = MenuFormat(registry.module, registry.externalNames).decode(menu).pages!!.catalog
        assertEquals(3, (catalog as Homes).count)

        assertTrue(registry.removeAll(owner))
        assertEquals(emptySet(), registry.externalNames)
        assertFailsWith<Exception> { MenuFormat(registry.module, registry.externalNames).decode(menu) }
    }

    @Test
    fun aCatalogNameCanBeTakenOnlyOnce() {
        val registry = TypeRegistry().also { Catalogs.register(it) }
        registry.addCatalog(owner, "test:homes", CatalogType { Homes(1) })
        assertFailsWith<IllegalArgumentException> { registry.addCatalog(owner, "test:homes", CatalogType { Homes(1) }) }
        assertFailsWith<IllegalArgumentException> { registry.addCatalog(owner, "homes", CatalogType { Homes(1) }) }
        assertFailsWith<IllegalArgumentException> { registry.addCatalog(null, Catalogs.Worlds::class, Catalogs.Worlds.serializer()) }
    }
}
