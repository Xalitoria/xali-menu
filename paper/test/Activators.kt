import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.xalitoria.menu.model.Activator
import net.xalitoria.menu.model.BlockAt
import net.xalitoria.menu.model.BlockClick
import net.xalitoria.menu.model.Click
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.TextMatch
import mc.xalitoria.menu.paper.runtime.ActivatorIndex
import mc.xalitoria.menu.paper.runtime.RegionChange
import mc.xalitoria.menu.paper.runtime.matches

class Activators {
    private val name: (String) -> String = { it.removePrefix("&a") }

    @Test
    fun anItemMatchesByMaterialNameAndModel() {
        val any = Activator.HeldItem()
        assertTrue(any.matches("stick", null, null, left = false, sneaking = false, name))

        val compass = Activator.HeldItem(material = "minecraft:Compass", name = "&aMenu", model = 7)
        assertTrue(compass.matches("compass", "Menu", 7, left = false, sneaking = false, name))
        assertFalse(compass.matches("clock", "Menu", 7, left = false, sneaking = false, name))
        assertFalse(compass.matches("compass", "Other", 7, left = false, sneaking = false, name))
        assertFalse(compass.matches("compass", "Menu", 8, left = false, sneaking = false, name))
        assertFalse(compass.matches("compass", null, 7, left = false, sneaking = false, name), "an item without the name")
    }

    @Test
    fun anItemMatchesTheClickAndSneaking() {
        val right = Activator.HeldItem(click = Click.RIGHT, sneaking = true)
        assertTrue(right.matches("stick", null, null, left = false, sneaking = true, name))
        assertFalse(right.matches("stick", null, null, left = true, sneaking = true, name))
        assertFalse(right.matches("stick", null, null, left = false, sneaking = false, name))
        assertTrue(Activator.HeldItem(click = Click.LEFT, sneaking = false).matches("stick", null, null, left = true, sneaking = false, name))
    }

    @Test
    fun chatMatchesAsTheMatchSays() {
        fun hit(match: TextMatch, text: String, said: String) = Activator.Chat(text, match).matches(said)
        assertTrue(hit(TextMatch.EQUALS, "menu", "menu"))
        assertFalse(hit(TextMatch.EQUALS, "menu", "Menu"))
        assertTrue(hit(TextMatch.EQUALS_IGNORE_CASE, "menu", "MENU"))
        assertFalse(hit(TextMatch.EQUALS_IGNORE_CASE, "menu", "the menu"))
        assertTrue(hit(TextMatch.CONTAINS, "menu", "open the Menu please"))
        assertTrue(hit(TextMatch.STARTS_WITH, "!shop", "!SHOP now"))
        assertFalse(hit(TextMatch.STARTS_WITH, "!shop", "go !shop"))
        assertTrue(hit(TextMatch.REGEX, "shop( \\d+)?", "shop 12"))
        assertFalse(hit(TextMatch.REGEX, "shop( \\d+)?", "shop x"), "the whole message must match")
        assertFailsWith<IllegalArgumentException> { Activator.Chat("(", TextMatch.REGEX) }
    }

    private fun block(
        activator: Activator.Block, type: String = "stone_button", click: BlockClick = BlockClick.RIGHT,
        world: String = "world", x: Int = 1, y: Int = 2, z: Int = 3, sneaking: Boolean = false, tags: Set<String> = emptySet(),
    ) = activator.matches(type, click, world, x, y, z, sneaking) { it in tags }

    @Test
    fun aBlockMatchesTypesAndTags() {
        assertTrue(block(Activator.Block()), "no types means any block")
        val buttons = Activator.Block(types = ["minecraft:Stone_Button", "oak_button"])
        assertTrue(block(buttons))
        assertTrue(block(buttons, type = "oak_button"))
        assertFalse(block(buttons, type = "lever"))
        val tagged = Activator.Block(types = ["#minecraft:buttons"])
        assertTrue(block(tagged, type = "birch_button", tags = setOf("buttons")))
        assertFalse(block(tagged, type = "birch_button"))
    }

    @Test
    fun aBlockMatchesThePlaceAndTheClick() {
        val at = Activator.Block(at = BlockAt(1, 2, 3, "world"))
        assertTrue(block(at))
        assertFalse(block(at, x = 9))
        assertFalse(block(at, world = "nether"))
        assertTrue(block(Activator.Block(at = BlockAt(1, 2, 3)), world = "nether"), "any world without one")

        assertFalse(block(Activator.Block(click = BlockClick.RIGHT), click = BlockClick.LEFT))
        assertTrue(block(Activator.Block(click = BlockClick.ANY), click = BlockClick.LEFT))
        assertTrue(block(Activator.Block(click = BlockClick.ANY), click = BlockClick.RIGHT))
        assertFalse(block(Activator.Block(click = BlockClick.ANY), click = BlockClick.PHYSICAL), "stepping isn't a click")
        assertTrue(block(Activator.Block(click = BlockClick.PHYSICAL), click = BlockClick.PHYSICAL))
        assertFalse(block(Activator.Block(sneaking = false), sneaking = true))
    }

    @Test
    fun anEntityMatchesTypeAndName() {
        val named = Activator.Entity(types = ["villager"], name = "&aShop")
        assertTrue(named.matches("villager", "Shop", false, name))
        assertFalse(named.matches("pig", "Shop", false, name))
        assertFalse(named.matches("villager", null, false, name))
        assertTrue(Activator.Entity().matches("pig", null, true, name), "no types and no name means any entity")
        assertFalse(Activator.Entity(sneaking = true).matches("pig", null, false, name))
    }

    @Test
    fun anNpcMatchesByNameOrIdAndClick() {
        val anyone = Activator.Npc()
        assertTrue(anyone.matches("Banker", "id-1", left = true, sneaking = false))

        val banker = Activator.Npc(npcs = ["banker", "OTHER"], click = Click.RIGHT, sneaking = false)
        assertTrue(banker.matches("Banker", "id-1", left = false, sneaking = false))
        assertTrue(banker.matches("x", "other", left = false, sneaking = false), "by id")
        assertTrue(banker.matches("Banker", "id-1", left = null, sneaking = false), "a click that is both")
        assertFalse(banker.matches("Banker", "id-1", left = true, sneaking = false))
        assertFalse(banker.matches("Smith", "id-2", left = false, sneaking = false))
        assertFalse(banker.matches("Banker", "id-1", left = false, sneaking = true))
    }

    @Test
    fun aRegionMatchesByIdOrAny() {
        assertTrue(Activator.RegionEnter().matches("spawn"))
        assertTrue(Activator.RegionEnter(["Spawn", "shop"]).matches("spawn"))
        assertFalse(Activator.RegionEnter(["shop"]).matches("spawn"))
        assertTrue(Activator.RegionLeave(["shop"]).matches("SHOP"))
    }

    @Test
    fun movingBetweenRegionsEntersAndLeavesTheDifference() {
        val inside = RegionChange.within(["a", "b"], ["b", "c", "d"])
        assertEquals(["c", "d"], inside.entered)
        assertEquals(["a"], inside.left)
        assertFalse(RegionChange.within(["a"], ["a"]).any)
        val away = RegionChange.between(["a"], ["a", "b"])
        assertEquals(["a", "b"], away.entered, "the same id in another world is another region")
        assertEquals(["a"], away.left)
    }

    @Test
    fun theIndexKeepsKindsApartAndInOrder() {
        val index = ActivatorIndex(
            mapOf(
                "b" to [Activator.Join(), Activator.Chat("x")],
                "a" to [Activator.Chat("y"), Activator.SwapHands()],
            ),
        )
        assertEquals(listOf("a" to "y", "b" to "x"), index.chats.map { it.menu to it.activator.text })
        assertEquals(listOf("b"), index.joins.map { it.menu })
        assertEquals(listOf("a"), index.swaps.map { it.menu })
        assertTrue(index.items.isEmpty() && index.blocks.isEmpty() && index.entities.isEmpty())
        assertFalse(index.isEmpty)
        assertTrue(ActivatorIndex.EMPTY.isEmpty)
    }

    @Test
    fun openWithIsRead() {
        val menu = Registry.format.decode(
            """
            title = "Shop"
            args = ["what"]
            open-with = [
              { type = item, material = compass, click = RIGHT, sneaking = false, args = ["compass"] },
              { type = join, delay = 40 },
              { type = chat, text = "!shop", match = STARTS_WITH, args = ["{message}"],
                require = { require = [{ condition { type = permission, node = "shop.chat" } }] } },
              { type = swap-hands, cancel = false, args = ["hands"] },
              { type = block, types = ["#minecraft:buttons", "lever"], at { x = 1, y = 64, z = -3, world = spawn }, click = ANY, args = ["{block}"] },
              { type = entity, types = [villager], name = "&aShop", args = ["npc"] },
              { type = npc, npcs = [banker], click = LEFT, args = ["{npc}"] },
              { type = region-enter, regions = [spawn], args = ["{region}"] },
              { type = region-leave }
            ]
            """.trimIndent()
        )
        val read = menu.openWith
        assertEquals(Activator.HeldItem(material = "compass", click = Click.RIGHT, sneaking = false, args = ["compass"]), read[0])
        assertEquals(Activator.Join(delay = 40), read[1])
        val chat = read[2] as Activator.Chat
        assertEquals("!shop" to TextMatch.STARTS_WITH, chat.text to chat.match)
        assertEquals(["{message}"], chat.args)
        assertTrue(chat.require != null && chat.cancel)
        assertEquals(Activator.SwapHands(cancel = false, args = ["hands"]), read[3])
        assertEquals(Activator.Block(["#minecraft:buttons", "lever"], BlockAt(1, 64, -3, "spawn"), BlockClick.ANY, args = ["{block}"]), read[4])
        assertEquals(Activator.Entity(["villager"], "&aShop", args = ["npc"]), read[5])
        assertEquals(Activator.Npc(["banker"], Click.LEFT, args = ["{npc}"]), read[6])
        assertEquals(Activator.RegionEnter(["spawn"], args = ["{region}"]), read[7])
        assertEquals(Activator.RegionLeave(), read[8])
    }

    @Test
    fun openWithIsWrittenAndReadBack() {
        val menu = Menu(
            title = "x",
            openWith = [Activator.HeldItem(material = "clock"), Activator.Chat("hi", TextMatch.CONTAINS), Activator.Block(["stone"], click = BlockClick.PHYSICAL)],
        )
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu)))
    }

    @Test
    fun anUnknownActivatorIsAnError() {
        assertFailsWith<Exception> { Registry.format.decode("title = x\nopen-with = [{ type = teleport }]") }
        assertFailsWith<Exception> { Registry.format.decode("title = x\nopen-with = [{ type = chat, text = \"(\", match = REGEX }]") }
    }
}
