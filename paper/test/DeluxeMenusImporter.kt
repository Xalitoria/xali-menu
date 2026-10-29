import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import mc.xalitoria.menu.paper.deluxemenus.*
import mc.xalitoria.menu.api.model.*
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.condition.Conditions

class DeluxeMenusImporter {
    /** A few of the old ids the fixtures use, and which names are old ones. */
    private val materials = object : Materials {
        override fun legacy(id: Int, data: Int) = when (id to data) {
            278 to 0 -> "diamond_pickaxe"
            35 to 14 -> "red_wool"
            else -> null
        }

        override fun legacy(name: String, data: Int) = when (name) {
            "monster_egg" -> "pig_spawn_egg"
            else -> null
        }

        override fun exists(name: String) = name != "monster_egg"
    }

    private val folder: Path = Path.of(javaClass.getResource("/deluxemenus/config.yml")!!.toURI()).parent
    private val report: ImportReport = DeluxeMenusImporter(Registry.format, materials).read(folder)

    private fun menu(name: String) = report.menus.first { it.name == name }.menu

    @Test
    fun readsEveryListedMenu() {
        assertEquals(["Jobs", "Shop", "help"], report.menus.map { it.name })
        assertEquals(["Gone: gui_menus/nope.yml not found"], report.failures)
    }

    @Test
    fun menuOptions() {
        val jobs = menu("Jobs")
        assertEquals("&8Jobs", jobs.title)
        assertEquals(27, jobs.size)
        assertEquals(["jobs", "jobs browse"], jobs.commands)

        val shop = menu("Shop")
        assertEquals(5.seconds, shop.refresh)
        assertEquals([Actions.Sound("ENTITY_PLAYER_LEVELUP", 1f, 2f)], shop.onOpen)
        assertEquals([Actions.Message("Bye")], shop.onClose)
        assertEquals(1, shop.open?.minimum)
        assertEquals(
            [
                Check(Conditions.Permission("shop.open"), optional = true),
                Check(Conditions.Money("100"), deny = [Actions.Message("&cYou need $100")]),
            ],
            shop.open?.require,
        )
    }

    @Test
    fun legacyMaterials() {
        val jobs = menu("Jobs").items
        assertEquals("diamond_pickaxe", jobs.getValue("1").material)
        assertEquals("red_wool", jobs.getValue("2").material)
        assertEquals("zombie_spawn_egg", menu("Shop").items.getValue("spawner").material)
        assertEquals("head:%player_name%", menu("help").items.getValue("info").material)
    }

    @Test
    fun itemOptions() {
        val sword = menu("Shop").items.getValue("sword")
        assertEquals([0, 2, 3, 4], sword.slots)
        assertEquals("%player_level%", sword.dynamicAmount)
        assertEquals(7, sword.model)
        assertEquals("#ff8000", sword.color)
        assertTrue(sword.update && sword.unbreakable)
        assertEquals(["attribute_modifiers", "enchantments", "can_break"], sword.hide)
        assertEquals([PotionEffect("haste", 200, 1)], sword.potion)
        assertEquals([BannerLayer("red", "base")], sword.banner)
        assertNotNull(sword.nbt).let {
            assertTrue("owner" in it && "tier:3" in it, it)
        }
        assertEquals(mapOf("sharpness" to 2), menu("Jobs").items.getValue("2").enchants - "silk_touch")
        assertEquals(["enchantments"], menu("Jobs").items.getValue("2").hide)
    }

    @Test
    fun conditions() {
        val checks = menu("Shop").items.getValue("sword").view!!.require.map { it.condition }
        assertEquals(
            [
                Conditions.Not(Conditions.Permission("shop.banned")),
                Conditions.Item("gold_ingot", 5),
                Conditions.Text("%vault_rank%", Conditions.Text.Op.EQUALS_IGNORE_CASE, "vip"),
                Conditions.Compare("%player_level%", Conditions.Compare.Op.GTE, "10"),
                Conditions.Regex("%player_name%", "[A-Z].*"),
                Conditions.Never,
            ],
            checks,
        )
    }

    @Test
    fun clicks() {
        val sword = menu("Shop").items.getValue("sword")
        assertEquals(
            [
                Actions.Console("give %player_name% diamond_sword"),
                Actions.Delay(20, Actions.Message("Enjoy")),
                Actions.Chance(25.0, Actions.Broadcast("Lucky")),
                Actions.Money("50", take = true),
                Actions.Connect("hub"),
            ],
            sword.click.left?.actions,
        )
        assertEquals([Actions.Close], sword.click.shiftLeft?.actions)
        assertEquals(
            [Check(Conditions.Money("50"), deny = [Actions.Message("&cNot enough")])],
            sword.click.left?.require?.require,
        )
        assertNull(sword.click.right)
        assertEquals([Actions.Open("Shop")], menu("Jobs").items.getValue("2").click.any?.actions)
    }

    @Test
    fun warnsAboutWhatDidNotComeAcross() {
        val shop = report.menus.first { it.name == "Shop" }.warnings
        assertTrue(shop.any { "[meta]" in it }, shop.toString())
        assertTrue(shop.any { "javascript" in it && "never pass" in it }, shop.toString())
        assertTrue(shop.any { "item 'broken'" in it && "no material" in it }, shop.toString())
        assertTrue("broken" !in menu("Shop").items)
        assertEquals([], report.menus.first { it.name == "Jobs" }.warnings)
    }

    @Test
    fun writtenFilesReadBack() {
        val importer = DeluxeMenusImporter(Registry.format, materials)
        val target = Files.createTempDirectory("menus")
        try {
            val first = importer.write(report, target)
            assertEquals(["Jobs.conf", "Shop.conf", "help.conf"], first.written.map { it.fileName.toString() }.sorted())
            for (imported in report.menus) {
                assertEquals(imported.menu, Registry.format.read(target.resolve("${imported.fileName}.conf")))
            }

            val again = importer.write(report, target)
            assertContentEquals(first.written, again.skipped)
            assertEquals([], again.written)
            assertEquals(3, importer.write(report, target, overwrite = true).written.size)
        } finally {
            target.toFile().deleteRecursively()
        }
    }
}
