import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.ActionType
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.api.ConditionType
import mc.xalitoria.menu.api.ItemSource
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.Settings
import mc.xalitoria.menu.api.model.Handler
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.format.MenuFormat
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** What another plugin would write: an action and a condition of its own, made from the keys of the menu file. */
data class Reward(val points: Int) : Action {
    override fun run(ctx: MenuContext) = Unit
}

data class Lucky(val odds: Int) : Condition {
    override fun test(ctx: MenuContext) = true
}

private val rewardType = ActionType { Reward(it.int("points", 1)) }
private val luckyType = ConditionType { Lucky(it.int("odds", 2)) }

class Extensions {
    private val owner = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args[0]
            "hashCode" -> 1
            else -> null
        }
    } as Plugin

    private fun registry() = TypeRegistry().also {
        Actions.register(it)
        Conditions.register(it)
    }

    private val menu = """
        title = "x"
        items.a {
          material = stone
          slot = 0
          click.any {
            require = { require = [{ condition = { type = "test:lucky", odds = 3 } }] }
            actions = [{ type = "test:reward", points = 7 }, { type = "close" }]
          }
        }
    """.trimIndent()

    @Test
    fun addedTypesAreReadFromMenuFiles() {
        val registry = registry()
        assertFailsWith<Exception> { MenuFormat(registry.module, registry.externalNames).decode(menu) }

        registry.addAction(owner, "test:reward", rewardType)
        registry.addCondition(owner, "test:lucky", luckyType)
        assertEquals(setOf("test:reward", "test:lucky"), registry.externalNames)
        val format = MenuFormat(registry.module, registry.externalNames)
        val handler: Handler = format.decode(menu).items.getValue("a").click.any!!
        assertEquals(listOf(Reward(7), Actions.Close), handler.actions)
        assertEquals(Lucky(3), handler.require?.require?.single()?.condition)
    }

    @Test
    fun whatAPluginAddedGoesWithIt() {
        val registry = registry()
        registry.addAction(owner, "test:reward", rewardType)
        registry.addCondition(owner, "test:lucky", luckyType)
        assertTrue(registry.removeAll(owner))
        assertEquals(emptySet(), registry.externalNames)
        assertFailsWith<Exception> { MenuFormat(registry.module, registry.externalNames).decode(menu) }
        assertEquals(false, registry.removeAll(owner))
    }

    @Test
    fun aNameCanBeTakenOnlyOnce() {
        val registry = registry()
        registry.addAction(owner, "test:reward", rewardType)
        assertFailsWith<IllegalArgumentException> { registry.addAction(owner, "test:reward", rewardType) }
        assertFailsWith<IllegalArgumentException> { registry.addAction(null, Actions.Close::class, Actions.Close.serializer()) }
        registry.addCondition(owner, "test:reward", luckyType) // actions and conditions are separate
    }

    @Test
    fun aNameHasAPluginInFrontOfIt() {
        val registry = registry()
        for (bad in listOf("reward", "Test:Reward", "test:", ":reward", "a b:c", "test:re ward")) {
            assertFailsWith<IllegalArgumentException>(bad) { registry.addAction(owner, bad, rewardType) }
        }
    }

    @Test
    fun theKeysReachTheFactoryInTheirOwnKinds() {
        val seen = mutableListOf<Settings>()
        val registry = registry()
        registry.addAction(owner, "test:keys", ActionType { seen += it; Actions.Close })
        MenuFormat(registry.module, registry.externalNames).decode("""
            title = "x"
            on-open = [{ type = "test:keys", text = hello, count = 3, ratio = 0.5, on = true, names = [a, b], nested { inner = 7 }, rows = [{ id = 1 }, { id = 2 }] }]
        """.trimIndent())
        val settings = seen.single()
        assertEquals("hello", settings.string("text"))
        assertEquals(3, settings.int("count"))
        assertEquals(0.5, settings.double("ratio"))
        assertEquals(true, settings.boolean("on"))
        assertEquals(listOf("a", "b"), settings.strings("names"))
        assertEquals(7, settings.section("nested")?.int("inner"))
        assertEquals(listOf(1, 2), settings.sections("rows").map { it.int("id") })
        assertEquals(null, settings.string("missing"))
        assertEquals(9, settings.int("missing", 9))
        assertEquals(null, settings.int("text"), "a value of the wrong kind reads as absent")
        assertFailsWith<IllegalArgumentException> { settings.requireString("missing") }
    }

    @Test
    fun aBadKeyBreaksTheMenuWithItsMessage() {
        val registry = registry()
        registry.addAction(owner, "test:strict", ActionType { Reward(it.requireString("target").length) })
        val error = assertFailsWith<Exception> {
            MenuFormat(registry.module, registry.externalNames).decode("title = x\non-open = [{ type = \"test:strict\" }]")
        }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { "'target' is missing" in it.message.orEmpty() })
    }

    @Test
    fun itemSourcesAreFoundByPrefix() {
        val registry = registry()
        val source = object : ItemSource {
            override val prefix = "Custom"
            override fun item(id: String): ItemStack? = null
        }
        registry.addSource(owner, source)
        assertEquals(setOf("custom"), registry.sources.keys)
        assertFailsWith<IllegalArgumentException> { registry.addSource(owner, source) }
        registry.removeAll(owner)
        assertEquals(emptySet(), registry.sources.keys)
    }
}
