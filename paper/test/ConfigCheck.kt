import mc.xalitoria.menu.paper.format.MenuFormatException
import mc.xalitoria.menu.paper.format.Suggest
import net.xalitoria.menu.model.Rarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What reading a menu file says about a file that is wrong, and the easy ways of writing one that are read as the one way. */
class ConfigCheck {
    private fun read(text: String) = Registry.format.decodeChecked("title = t\n$text")

    private fun problems(text: String): List<String> = assertFailsWith<MenuFormatException> { read(text) }.problems.map { it.toString() }

    private fun warnings(text: String): List<String> = read(text).warnings.map { it.toString() }

    @Test
    fun aKeyNothingReadsIsWarnedAboutWithTheKeyItWasMeantToBe() {
        val found = warnings("items.a { material = stone, slott = 1 }")
        assertEquals(1, found.size)
        assertTrue("items.a.slott" in found.single() && "did you mean 'slot'?" in found.single(), found.single())
        assertTrue("max-stack-size" in warnings("items.a { material = stone, slot = 1, max_stack_size = 3 }").single(), "the style of another config is told as the key it is")
        assertTrue("did you mean" !in warnings("items.a { material = stone, slot = 1, zebra = 3 }").single(), "no guess when nothing is close")
        assertTrue("titel" in Registry.format.decodeChecked("title = t\ntitel = x").warnings.single().toString())
    }

    @Test
    fun theFileStillLoadsWithAWarning() {
        assertEquals("stone", read("items.a { material = stone, slot = 1, slott = 2 }").menu.items.getValue("a").material)
    }

    @Test
    fun aMistakeIsToldWithWhereItIsAndNotWhatTheReaderTrippedOn() {
        assertEquals(listOf("items.a.material (line 2): is required"), problems("items.a { slot = 1 }").map { it })
        assertTrue("items.a.rarity" in problems("items.a { material = stone, slot = 1, rarity = LEGENDARY }").single())
        assertTrue("one of COMMON, UNCOMMON, RARE, EPIC" in problems("items.a { material = stone, slot = 1, rarity = LEGENDARY }").single())
        assertTrue("items.a.slot" in problems("items.a { material = stone, slot = abc }").single() && "a whole number" in problems("items.a { material = stone, slot = abc }").single())
        assertTrue("refresh" in problems("refresh = soon").single() && "5s" in problems("refresh = soon").single())
    }

    @Test
    fun anUnknownTypeOfAnActionSuggestsTheOneMeant() {
        val found = problems("items.a { material = stone, slot = 1, click.left.actions = [{ type = clse }] }").single()
        assertTrue("items.a.click.left.actions[0].type" in found, found)
        assertTrue("'clse' is not an action" in found, found)
        assertTrue("did you mean 'close'?" in found, found)
        assertTrue("type" in problems("items.a { material = stone, slot = 1, click.left.actions = [{ text = hi }] }").single())
    }

    @Test
    fun everyProblemIsToldAtOnce() {
        val error = assertFailsWith<MenuFormatException> { read("items.a { slot = abc }\nitems.b { material = stone, rarity = X }") }
        assertEquals(3, error.problems.size, error.message)
    }

    @Test
    fun aSingleValueMayBeWrittenWithoutTheBrackets() {
        val item = read("items.a { material = stone, slots = 5, click.left.actions = { type = close }, lore = \"one line\" }").menu.items.getValue("a")
        assertEquals([5], item.slots)
        assertEquals(1, item.click.left!!.actions.size)
        assertEquals(["one line"], item.lore)
    }

    @Test
    fun anEnumMayBeWrittenInAnyCase() {
        assertEquals(Rarity.EPIC, read("items.a { material = stone, slot = 1, rarity = epic }").menu.items.getValue("a").rarity)
    }

    @Test
    fun numbersAndSwitchesMayBeTextOfThem() {
        val item = read("items.a { material = stone, slot = \"3\", unbreakable = \"true\", amount = \"%x%\" }").menu.items.getValue("a")
        assertEquals(3, item.slot)
    }

    @Test
    fun aValueOfTheWrongKindSaysWhatItShouldBe() {
        assertTrue("an object" in problems("items = 5").single() || "set of names" in problems("items = 5").single())
        assertTrue("text" in problems("items.a { material = [stone], slot = 1 }").single())
        assertTrue("items.a.slot" in problems("items.a { material = stone, slot { x = 1 } }").single())
    }

    @Test
    fun theClosestWordIsFoundByStyleThenByEdits() {
        assertEquals("on-open", Suggest.closest("on_open", ["on-open", "on-close"]))
        assertEquals("on-open", Suggest.closest("onOpen", ["on-open", "on-close"]))
        assertEquals("priority", Suggest.closest("priorty", ["slot", "slots", "priority"]))
        assertEquals(null, Suggest.closest("zebra", ["slot", "slots"]))
        assertEquals(null, Suggest.closest("slot", ["slot"]), "a word that is a candidate needs no correction")
        assertEquals(3, Suggest.distance("kitten", "sitting"))
    }

    @Test
    fun theMenusThatComeWithThePluginAndTheLiveTestsAreReadWithNoWarning() {
        val files = listOf("resources", "../live/menus").flatMap { dir -> java.io.File("$dir").listFiles { f -> f.extension == "conf" }?.toList().orEmpty() }
        assertTrue(files.size > 5, "the menus were not found from ${java.io.File(".").absolutePath}")
        // `i.conf` is the live test of what the plugin says about wrong items, so it has some.
        for (file in files.filter { it.name != "i.conf" }) {
            val read = runCatching { Registry.format.readChecked(file.toPath()) }
            // A menu of the live tests that uses another plugin's type is not read without it.
            if (read.isFailure) { assertTrue("is not" in read.exceptionOrNull()!!.message.orEmpty() && ":" in read.exceptionOrNull()!!.message.orEmpty(), "${file.name}: ${read.exceptionOrNull()?.message}"); continue }
            assertEquals([], read.getOrThrow().warnings, file.name)
        }
    }

    @Test
    fun aNumberOutOfItsRangeIsToldAndTheMenuStillLoads() {
        assertEquals(["items.a.amount (line 2): should be from 1 to 99, not 0"], warnings("items.a { material = stone, slot = 1, amount = 0 }"))
        assertEquals(["items.a.inventory-slots[1] (line 2): should be from 0 to 35, not 40"], warnings("items.a { material = stone, inventory-slots = [3, 40] }"))
        assertEquals(["items.a.slots[0] (line 2): can't be below 0, not -1"], warnings("items.a { material = stone, slots = [-1] }"))
        assertEquals(["size (line 2): should be from 9 to 54, not 100"], warnings("size = 100"))
        assertEquals([], warnings("items.a { material = stone, slot = 1, amount = \"%level%\", damage = 5 }"), "text with a placeholder is only known when the item is drawn")
    }

    @Test
    fun aNumberOutOfItsRangeRefusesTheFileWhereTheClassCannotBeMadeWithIt() {
        val found = problems("items.a { material = stone, slot = 1, accept { then = [{ type = close }], amount = 0 } }").single()
        assertTrue("items.a.accept.amount" in found && "from 1 to 99" in found, found)
    }

    @Test
    fun theNumbersOfActionsAndConditionsHaveLimitsToo() {
        fun click(action: String) = "items.a { material = stone, slot = 1, click.left.actions = [$action] }"
        // What an action refuses when it is made is told with where it is, instead of the reader's own words.
        val potion = problems(click("{ type = potion, effect = speed, amplifier = 300 }")).single()
        assertTrue("items.a.click.left.actions[0].amplifier" in potion && "from 0 to 255, not 300" in potion, potion)
        assertTrue("from 1 to 1000" in problems(click("{ type = particle, particle = flame, count = 0 }")).single())
        assertTrue("can't be below 1, not 0" in problems(click("{ type = take-item, material = stone, amount = 0 }")).single())
        assertTrue("from 0 to 100, not 120" in problems("items.a { material = stone, slot = 1, view.require = [{ condition { type = chance, percent = 120 } }] }").single())
        // What is still made is warned about.
        assertEquals(["items.a.click.left.actions[0].percent (line 2): should be from 0 to 100, not 150"], warnings(click("{ type = chance, percent = 150, action = { type = close } }")))
        assertEquals(["items.a.click.left.actions[0].ticks (line 2): can't be below 0, not -5"], warnings(click("{ type = delay, ticks = -5, action = { type = close } }")))
        assertEquals([], warnings(click("{ type = delay, ticks = 20, action = { type = close } }")))
    }

    @Test
    fun aRefusedFileAlsoTellsTheWarningsSoTheyAreFixedInOneRound() {
        val e = assertFailsWith<MenuFormatException> { read("items.a { material = stone, slott = 1, click.left.actions = [{ type = clse }] }") }
        assertEquals(1, e.problems.size)
        assertEquals(1, e.warnings.size)
        assertTrue("'clse' is not an action" in e.message!! && "would not stop it loading" in e.message!! && "did you mean 'slot'?" in e.message!!, e.message)
    }
}
