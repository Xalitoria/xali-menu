import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import mc.xalitoria.menu.api.model.Book
import mc.xalitoria.menu.api.model.Fish
import mc.xalitoria.menu.api.model.Firework
import mc.xalitoria.menu.api.model.FireworkEffect
import mc.xalitoria.menu.api.model.FireworkType
import mc.xalitoria.menu.api.model.Food
import mc.xalitoria.menu.api.model.Item
import mc.xalitoria.menu.api.model.Menu
import mc.xalitoria.menu.api.model.Rarity
import mc.xalitoria.menu.api.model.Trim

class ItemProperties {
    @Test
    fun theNewPropertiesAreRead() {
        val menu = Registry.format.decode(
            """
            title = "T"
            items {
              a {
                material = golden_apple
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
              }
            }
            """.trimIndent()
        )
        val item = menu.items.getValue("a")
        assertEquals(5, item.damage)
        assertEquals("&6Apple", item.itemName)
        assertEquals(Rarity.EPIC, item.rarity)
        assertEquals(true, item.glow)
        assertEquals(16, item.maxStackSize)
        assertEquals(true, item.hideTooltip)
        assertEquals("mypack:crown", item.itemModel)
        assertEquals(mapOf("sharpness" to 3), item.storedEnchants)
        assertEquals("strong_healing", item.potionType)
        assertEquals(Food(4, 2.5f, true), item.food)
        assertEquals(Trim("coast", "gold"), item.trim)
        assertEquals(Book(["one", "two"], "T", "A"), item.book)
        assertEquals(Firework([FireworkEffect(FireworkType.STAR, ["#ff0000", "#00ff00"], ["#0000ff"], flicker = true)], power = 2), item.firework)
        assertEquals(Fish("KOB", "RED"), item.fish)
        assertNull(Item("stone").glow, "a property that is left out stays out")
    }

    @Test
    fun theNewPropertiesAreWrittenAndReadBack() {
        val item = Item(
            "golden_apple", slot = 0, damage = 5, itemName = "x", rarity = Rarity.RARE, glow = false, maxStackSize = 3,
            food = Food(2), trim = Trim("coast", "gold"), book = Book(["p"]),
            firework = Firework([FireworkEffect(colors = ["#ffffff"])]), fish = Fish("KOB"),
        )
        val menu = Menu(title = "t", items = mapOf("a" to item))
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])))
    }
}
