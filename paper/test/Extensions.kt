import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.runtime.ActivatorIndex
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActionType
import net.xalitoria.menu.ActivatorFilter
import net.xalitoria.menu.ActivatorType
import net.xalitoria.menu.Completer
import net.xalitoria.menu.Condition
import net.xalitoria.menu.ConditionType
import net.xalitoria.menu.ItemProperty
import net.xalitoria.menu.ItemPropertyType
import net.xalitoria.menu.ItemSource
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.Settings
import net.xalitoria.menu.ValidatedItemProperty
import net.xalitoria.menu.model.Activator
import net.xalitoria.menu.model.Handler
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What another plugin would write: an action and a condition of its own, made from the keys of the menu file. */
data class Reward(val points: Int) : Action {
    override fun run(ctx: MenuContext) = Unit
}

data class Lucky(val odds: Int) : Condition {
    override fun test(ctx: MenuContext) = true
}

data class TagProp(val key: String, val value: String, val problems: List<String> = emptyList()) : ValidatedItemProperty {
    override fun apply(item: ItemStack, ctx: MenuContext) = Unit
    override fun validate(): List<String> = problems
}

private val rewardType = ActionType { Reward(it.int("points", 1)) }
private val luckyType = ConditionType { Lucky(it.int("odds", 2)) }
private val tagPropType = ItemPropertyType {
    TagProp(
        key = it.string("key", "default"),
        value = it.requireString("value"),
        problems = if (it.string("value") == "bad") listOf("'bad' is not allowed") else emptyList(),
    )
}

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
        for (bad in listOf("reward", "Test:Reward", "test:", ":reward", "a b:c", "test:re ward", "..:reward", ".:reward", ".hidden:reward", "a..b:reward", "a.:reward")) {
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

    private val emoteMenu = """
        title = "x"
        open-with = [
          { type = "test:emote", name = wave, repeat = 2, cancel = false, args = ["{emote}"], require { require = [{ condition = { type = "test:lucky", odds = 3 } }] } }
          { type = "join" }
        ]
    """.trimIndent()

    @Test
    fun anAddedActivatorIsReadWithTheKeysEveryActivatorHas() {
        val registry = registry()
        registry.addCondition(owner, "test:lucky", luckyType)
        registry.addActivator(owner, "test:emote", ActivatorType { ActivatorFilter { values -> values["emote"] == it.requireString("name") } })
        assertEquals(setOf("test:emote"), registry.activatorNames)
        val menu = MenuFormat(registry.module, registry.externalNames, registry.activatorNames).decode(emoteMenu)
        val added = menu.openWith.first() as Activator.Addon
        assertEquals("test:emote", added.name)
        assertEquals(false, added.cancel)
        assertEquals(listOf("{emote}"), added.args)
        assertEquals(Lucky(3), added.require?.require?.single()?.condition, "the gate is read like any other")
        assertEquals(Activator.Join(), menu.openWith[1], "the built-in ones are read as before")
        val values = com.typesafe.config.ConfigFactory.parseString(added.values).root().unwrapped()
        assertEquals(mapOf<String, Any?>("name" to "wave", "repeat" to 2), values, "only the plugin's own keys are left for it")
    }

    @Test
    fun anAddedActivatorOnlyFiresWhenItsFilterAgrees() {
        val registry = registry()
        registry.addActivator(owner, "test:emote", ActivatorType { ActivatorFilter { values -> values["emote"] == it.requireString("name") } })
        val menu = MenuFormat(registry.module, registry.externalNames, registry.activatorNames).decode("""
            title = x
            open-with = [{ type = "test:emote", name = wave }, { type = "test:emote", name = bow }]
        """.trimIndent())
        val index = ActivatorIndex(mapOf("m" to menu.openWith)) { registry.activator(it.name)?.create(Settings(com.typesafe.config.ConfigFactory.parseString(it.values).root().unwrapped())) }
        assertEquals(2, index.added.size)
        assertEquals(listOf(true, false), index.added.map { it.filter.matches(mapOf("emote" to "wave")) })
        assertEquals(0, ActivatorIndex(mapOf("m" to menu.openWith)).added.size, "no plugin, no activator")
    }

    @Test
    fun anAddedActivatorGoesWithItsPlugin() {
        val registry = registry()
        registry.addActivator(owner, "test:emote", ActivatorType { ActivatorFilter { true } })
        assertFailsWith<IllegalArgumentException> { registry.addActivator(owner, "test:emote", ActivatorType { ActivatorFilter { true } }) }
        assertFailsWith<IllegalArgumentException> { registry.addActivator(owner, "emote", ActivatorType { ActivatorFilter { true } }) }
        assertTrue(registry.removeAll(owner))
        assertEquals(emptySet(), registry.activatorNames)
        assertEquals(null, registry.activator("test:emote"))
        assertFailsWith<Exception> { MenuFormat(registry.module, registry.externalNames, registry.activatorNames).decode(emoteMenu) }
    }

    @Test
    fun addedItemPropertiesAreReadFromObjectsAndScalarsAndShareNamesWithActions() {
        val registry = registry().also { mc.xalitoria.menu.paper.catalog.Catalogs.register(it) }
        registry.addAction(owner, "test:tag", rewardType)
        registry.addItemProperty(owner, "test:tag", tagPropType, mapOf("key" to "quest", "value" to "intro"))
        registry.addItemProperty(owner, "test:flag", ItemPropertyType { TagProp("flag", it.requireString("value")) })
        assertEquals(setOf("test:tag", "test:flag"), registry.propertyNames)
        assertEquals(mapOf("key" to "quest", "value" to "intro"), registry.itemPropertyInfo["test:tag"])
        assertEquals(emptyMap(), registry.itemPropertyInfo["test:flag"])

        val format = MenuFormat(registry.module, registry.externalNames, registry.activatorNames, registry.propertyNames)
        val read = format.decodeChecked(
            """
            title = "x"
            items.a {
              material = stone
              slot = 0
              "test:tag" { key = "quest", value = "intro" }
              "test:flag" = "vip"
              click.any.actions = [{ type = "test:tag", points = 5 }]
            }
            pages {
              catalog { type = range, from = 1, to = 3 }
              slots = [1]
              item {
                material = paper
                "test:tag" { key = "page", value = "{value}" }
              }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.warnings)
        val item = read.menu.items.getValue("a")
        assertEquals(TagProp("quest", "intro"), item.properties["test:tag"])
        assertEquals(TagProp("flag", "vip"), item.properties["test:flag"])
        assertEquals(listOf(Reward(5)), item.click.any!!.actions, "an action sharing the name with an item property is not broken or double-packed")
        assertEquals(TagProp("page", "{value}"), read.menu.pages!!.item!!.properties["test:tag"], "works on paged item templates too")
    }

    @Test
    fun unknownOrBrokenItemPropertiesReportClearErrorsAndGoWithTheirOwner() {
        val registry = registry()
        registry.addItemProperty(owner, "test:tag", tagPropType)
        val format = MenuFormat(registry.module, registry.externalNames, registry.activatorNames, registry.propertyNames)

        // Unknown namespaced property fails with suggestion.
        val unknown = assertFailsWith<mc.xalitoria.menu.paper.format.MenuFormatException> {
            format.decode("""title = x\nitems.a { material = stone, slot = 0, "test:tga" { value = "v" } }""".replace("\\n", "\n"))
        }
        assertTrue("is not an item property" in unknown.message!! && "did you mean 'test:tag'?" in unknown.message!!, unknown.message)

        // Missing required key in property factory fails decode with its message.
        val missingKey = assertFailsWith<Exception> {
            format.decode("""title = x\nitems.a { material = stone, slot = 0, "test:tag" { key = "k" } }""".replace("\\n", "\n"))
        }
        assertTrue(generateSequence<Throwable>(missingKey) { it.cause }.any { "'value' is missing" in it.message.orEmpty() })

        // ValidatedItemProperty reports non-fatal problems.
        val badItem = format.decode("""title = x\nitems.a { material = stone, slot = 0, "test:tag" { value = "bad" } }""".replace("\\n", "\n")).items.getValue("a")
        val prop = badItem.properties.getValue("test:tag") as ValidatedItemProperty
        assertEquals(listOf("'bad' is not allowed"), prop.validate())

        // Writing raw `properties` on an item produces a warning.
        val rawProps = format.decodeChecked("""title = x\nitems.a { material = stone, slot = 0, properties {} }""".replace("\\n", "\n"))
        assertEquals(1, rawProps.warnings.size)
        assertTrue("plugin:name" in rawProps.warnings.single().message)

        // Duplicate name and bad format are refused; removing owner clears everything.
        assertFailsWith<IllegalArgumentException> { registry.addItemProperty(owner, "test:tag", tagPropType) }
        assertFailsWith<IllegalArgumentException> { registry.addItemProperty(owner, "tag", tagPropType) }
        assertTrue(registry.removeAll(owner))
        assertEquals(emptySet(), registry.propertyNames)
        assertEquals(emptyMap(), registry.itemPropertyInfo)
    }

    @Test
    fun addedCompletersExpandInMenuArgsAndGoWithTheirOwner() {
        val registry = registry()
        registry.addCompleter(owner, "test:kits", Completer { listOf("tools", "dtools", "firework") })
        assertEquals(setOf("test:kits"), registry.completerNames)
        val sender = Proxy.newProxyInstance(org.bukkit.command.CommandSender::class.java.classLoader, arrayOf(org.bukkit.command.CommandSender::class.java)) { _, _, _ -> null } as org.bukkit.command.CommandSender
        val complete = mapOf("kit" to listOf("@test:kits"))
        val expand: (String) -> Collection<String> = { kind -> registry.completer(kind.removePrefix("@"))?.suggest(sender).orEmpty() }
        assertEquals(listOf("tools", "dtools", "firework"), mc.xalitoria.menu.paper.runtime.MenuArgs.suggestions(listOf("kit"), complete, listOf(""), expand))
        assertEquals(listOf("dtools"), mc.xalitoria.menu.paper.runtime.MenuArgs.suggestions(listOf("kit"), complete, listOf("dt"), expand))
        assertFailsWith<IllegalArgumentException> { registry.addCompleter(owner, "test:kits", Completer { emptyList() }) }
        assertFailsWith<IllegalArgumentException> { registry.addCompleter(owner, "kits", Completer { emptyList() }) }
        assertTrue(registry.removeAll(owner))
        assertEquals(emptySet(), registry.completerNames)
        assertEquals(null, registry.completer("test:kits"))
    }
}
