import net.xalitoria.menu.model.AttributeModifier
import net.xalitoria.menu.model.AttributeOperation
import net.xalitoria.menu.model.Book
import net.xalitoria.menu.model.DynamicBoolean
import net.xalitoria.menu.model.DynamicInt
import net.xalitoria.menu.model.Equippable
import net.xalitoria.menu.model.Firework
import net.xalitoria.menu.model.FireworkEffect
import net.xalitoria.menu.model.FireworkType
import net.xalitoria.menu.model.Fish
import net.xalitoria.menu.model.Food
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.Rarity
import net.xalitoria.menu.model.Tool
import net.xalitoria.menu.model.ToolRule
import net.xalitoria.menu.model.Trim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemProperties {
    @Test
    fun theNewPropertiesAreRead() {
        val menu = Registry.format.decode(
            """
            title = "T"
            items {
              a {
                material = golden_apple
                skull = "audizian"
                slot = 0
                damage = 5
                item-name = "&6Apple"
                rarity = EPIC
                glow = true
                max-stack-size = 16
                hide-tooltip = true
                item-model = "mypack:crown"
                stored-enchants { sharpness = 3 }
                potion-type = strong_healing
                food { nutrition = 4, saturation = 2.5, always-edible = true }
                trim { pattern = coast, material = gold }
                book { pages = ["one", "two"], title = T, author = A }
                firework { power = 2, effects = [ { type = STAR, colors = ["#ff0000", "#00ff00"], fade = ["#0000ff"], flicker = true } ] }
                fish { pattern = KOB, body-color = RED }
                attributes = [
                  { attribute = attack_damage, amount = 4.5 }
                  { attribute = max_health, amount = 0.5, operation = MULTIPLY_BASE, slot = chest }
                ]
                tooltip-style = "mypack:fancy"
                equippable { slot = HEAD, model = "mypack:crown", swappable = false }
                tool { mining-speed = 2.5, damage-per-block = 2, rules = [ { blocks = ["#minecraft:mineable/pickaxe", stone], speed = 8, drops = true } ] }
              }
            }
            """.trimIndent()
        )
        val item = menu.items.getValue("a")
        assertEquals("audizian", item.skull)
        assertEquals(DynamicInt(5), item.damage)
        assertEquals("&6Apple", item.itemName)
        assertEquals(Rarity.EPIC, item.rarity)
        assertEquals(DynamicBoolean(true), item.glow)
        assertEquals(DynamicInt(16), item.maxStackSize)
        assertEquals(DynamicBoolean(true), item.hideTooltip)
        assertEquals("mypack:crown", item.itemModel)
        assertEquals(mapOf("sharpness" to 3), item.storedEnchants)
        assertEquals("strong_healing", item.potionType)
        assertEquals(Food(4, 2.5f, true), item.food)
        assertEquals(Trim("coast", "gold"), item.trim)
        assertEquals(Book(["one", "two"], "T", "A"), item.book)
        assertEquals(Firework([FireworkEffect(FireworkType.STAR, ["#ff0000", "#00ff00"], ["#0000ff"], flicker = true)], power = 2), item.firework)
        assertEquals(Fish("KOB", "RED"), item.fish)
        assertEquals(
            [AttributeModifier("attack_damage", 4.5), AttributeModifier("max_health", 0.5, AttributeOperation.MULTIPLY_BASE, "chest")],
            item.attributes,
        )
        assertEquals("mypack:fancy", item.tooltipStyle)
        assertEquals(Equippable("HEAD", "mypack:crown", swappable = false), item.equippable)
        assertEquals(Tool(2.5f, 2, rules = [ToolRule(["#minecraft:mineable/pickaxe", "stone"], 8f, true)]), item.tool)
        assertNull(Item("stone").glow, "a property that is left out stays out")
    }

    @Test
    fun theNewPropertiesAreWrittenAndReadBack() {
        val item = Item(
            "golden_apple", slot = 0, damage = DynamicInt(5), itemName = "x", rarity = Rarity.RARE, glow = DynamicBoolean(false), maxStackSize = DynamicInt(3),
            food = Food(2), trim = Trim("coast", "gold"), book = Book(["p"]),
            firework = Firework([FireworkEffect(colors = ["#ffffff"])]), fish = Fish("KOB"),
            attributes = [AttributeModifier("attack_damage", 2.0, AttributeOperation.MULTIPLY_TOTAL, "hand")], tooltipStyle = "a:b",
            equippable = Equippable("CHEST", cameraOverlay = "a:b", damageOnHurt = false),
            tool = Tool(rules = [ToolRule(["stone"])]),
        )
        val menu = Menu(title = "t", items = mapOf("a" to item))
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])))
    }

    @Test
    fun aNumberOrASwitchMayBePlaceholderText() {
        val menu = Registry.format.decode(
            """
            title = t
            items {
              a {
                material = stone, slot = 0
                amount = "%player_level%"
                damage = 7
                model = "%rank_model%"
                glow = "%player_is_op%"
                unbreakable = true
              }
            }
            """.trimIndent()
        )
        val item = menu.items.getValue("a")
        assertEquals(DynamicInt("%player_level%"), item.amount)
        assertNull(item.amount.fixed, "text with a placeholder is only worked out when the item is drawn")
        assertEquals(7, item.damage?.fixed)
        assertEquals(DynamicBoolean("%player_is_op%"), item.glow)
        assertEquals(true, item.unbreakable.fixed)
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])), "what was written as text stays text, a number stays a number")
        val written = Registry.format.encode(Menu(title = "t", items = mapOf("a" to Item("stone", amount = DynamicInt(3)))), [])
        assertEquals(true, Regex("""amount\s*=\s*3\b""").containsMatchIn(written), "a fixed number is written as a number: $written")
    }

    @Test
    fun placeholderTextWorksOutToANumberOrASwitch() {
        val fill = { text: String -> text.replace("%level%", "12.0").replace("%op%", "Yes") }
        assertEquals(12, DynamicInt("%level%").resolve(fill))
        assertEquals(5, DynamicInt(5).resolve(fill))
        assertNull(DynamicInt("%name%").resolve(fill), "text that is not a number gives nothing, and the default is used")
        assertEquals(true, DynamicBoolean("%op%").resolve(fill))
        assertEquals(false, DynamicBoolean("off").resolve(fill))
        assertNull(DynamicBoolean("%level%").resolve(fill))
    }

    @Test
    fun headsCachesMissingOfflineSkinsWithoutThrowingNpe() {
        var lookups = 0
        var redrawn = 0
        val scheduler = java.lang.reflect.Proxy.newProxyInstance(
            io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java.classLoader,
            arrayOf(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java),
        ) { _, method, args ->
            if (method.name == "runNow") {
                @Suppress("UNCHECKED_CAST")
                (args[1] as java.util.function.Consumer<Any?>).accept(null)
            }
            null
        } as io.papermc.paper.threadedregions.scheduler.AsyncScheduler
        val emptyProfile = java.lang.reflect.Proxy.newProxyInstance(
            com.destroystokyo.paper.profile.PlayerProfile::class.java.classLoader,
            arrayOf(com.destroystokyo.paper.profile.PlayerProfile::class.java),
        ) { _, method, _ -> if (method.name == "complete") false else null } as com.destroystokyo.paper.profile.PlayerProfile
        val server = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.Server::class.java.classLoader,
            arrayOf(org.bukkit.Server::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayerExact" -> null
                "getAsyncScheduler" -> scheduler
                "createProfile" -> {
                    lookups++
                    if (lookups == 1) emptyProfile else throw RuntimeException("mojang down")
                }
                else -> null
            }
        } as org.bukkit.Server
        val plugin = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getServer" -> server
                "getLogger" -> java.util.logging.Logger.getAnonymousLogger()
                else -> null
            }
        } as org.bukkit.plugin.Plugin

        val heads = mc.xalitoria.menu.paper.hook.Heads(plugin, skinsRestorer = false)
        assertNull(heads.skinUrl("NoSuchPlayer"))
        assertNull(heads.skinUrl("NoSuchPlayer"), "cached missing entry does not re-query")
        assertEquals(1, lookups)

        // Trigger the throwing branch via async lookup too (no NPE in ConcurrentHashMap).
        assertNull(heads.named("BrokenLookup") { redrawn++ })
        assertNull(heads.named("BrokenLookup") { redrawn++ })
        assertEquals(2, lookups)
        assertEquals(1, redrawn)
    }
}
