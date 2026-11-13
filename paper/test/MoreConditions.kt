import mc.xalitoria.menu.paper.action.Social
import mc.xalitoria.menu.paper.condition.Logic
import mc.xalitoria.menu.paper.condition.PlayerConditions
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.Placeholders
import mc.xalitoria.menu.paper.runtime.explain
import net.xalitoria.menu.Action
import net.xalitoria.menu.Condition
import net.xalitoria.menu.MenuContext
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoreConditions {
    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) = Unit
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
        override fun page(player: Player, relative: Boolean, value: Int) = Unit
    }

    private val ctx = Context(Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player, "test", emptyMap(), host)

    private val yes = object : Condition { override fun test(ctx: MenuContext) = true }
    private val no = object : Condition { override fun test(ctx: MenuContext) = false }

    /** Counts how often it was asked. */
    private class Counting(val answer: Boolean) : Condition {
        var asked = 0
        override fun test(ctx: MenuContext): Boolean { asked++; return answer }
    }

    /** A context whose player stands at [x], [y], [z] in the world called [world]. */
    private fun standingAt(world: String, x: Double, y: Double, z: Double): Context {
        val level = Proxy.newProxyInstance(org.bukkit.World::class.java.classLoader, arrayOf(org.bukkit.World::class.java)) { _, method, _ ->
            if (method.name == "getName") world else null
        } as org.bukkit.World
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            if (method.name == "getLocation") org.bukkit.Location(level, x, y, z) else null
        } as Player
        return Context(player, "test", emptyMap(), host)
    }

    @Test
    fun nearIsLessThanTheDistanceAndInTheWorld() {
        val near = PlayerConditions.Near("world", 0.0, 64.0, 0.0, 5.0)
        assertTrue(near.test(standingAt("world", 3.0, 64.0, 3.0)))
        assertFalse(near.test(standingAt("world", 3.0, 68.0, 3.0)), "the height counts")
        assertFalse(near.test(standingAt("world", 5.0, 64.0, 0.0)), "exactly the distance is not less")
        assertFalse(near.test(standingAt("nether", 0.0, 64.0, 0.0)), "another world")
        assertTrue(near.test(standingAt("World", 0.0, 64.0, 0.0)), "the name of the world in any case")
    }

    @Test
    fun andOrAndOneOf() {
        assertTrue(Logic.And([yes, yes]).test(ctx))
        assertFalse(Logic.And([yes, no]).test(ctx))
        assertTrue(Logic.And([]).test(ctx), "nothing to fail")
        assertTrue(Logic.Or([no, yes]).test(ctx))
        assertFalse(Logic.Or([no, no]).test(ctx))
        assertFalse(Logic.Or([]).test(ctx))
        assertTrue(Logic.OneOf([no, yes, no]).test(ctx))
        assertFalse(Logic.OneOf([yes, yes]).test(ctx))
        assertFalse(Logic.OneOf([no]).test(ctx))
    }

    @Test
    fun atLeastCounts() {
        assertTrue(Logic.AtLeast(2, [yes, no, yes]).test(ctx))
        assertFalse(Logic.AtLeast(3, [yes, no, yes]).test(ctx))
        assertTrue(Logic.AtLeast(0, []).test(ctx))
        assertFailsWith<IllegalArgumentException> { Logic.AtLeast(-1, []) }
    }

    @Test
    fun combinationsStopAsSoonAsTheyKnow() {
        val first = Counting(false)
        val second = Counting(true)
        Logic.And([first, second]).test(ctx)
        assertEquals(1, first.asked)
        assertEquals(0, second.asked)
        val a = Counting(true)
        val b = Counting(true)
        Logic.Or([a, b]).test(ctx)
        assertEquals(0, b.asked)
        val c = Counting(true)
        val d = Counting(true)
        val e = Counting(true)
        Logic.OneOf([c, d, e]).test(ctx)
        assertEquals(0, e.asked, "two holding already settles it")
    }

    @Test
    fun chanceIsAtTheEnds() {
        repeat(50) {
            assertTrue(Logic.Chance(100.0).test(ctx))
            assertFalse(Logic.Chance(0.0).test(ctx))
        }
        assertFailsWith<IllegalArgumentException> { Logic.Chance(101.0) }
    }

    @Test
    fun aRangeHasInclusiveLimitsThatMayBePlaceholders() {
        assertTrue(PlayerConditions.within(ctx, 5.0, "5", "10"))
        assertTrue(PlayerConditions.within(ctx, 10.0, "5", "10"))
        assertFalse(PlayerConditions.within(ctx, 4.9, "5", null))
        assertFalse(PlayerConditions.within(ctx, 11.0, null, "10"))
        assertTrue(PlayerConditions.within(ctx, 1e9, null, null))
        assertFalse(PlayerConditions.within(ctx, 5.0, "five", null), "a limit that isn't a number lets nothing through")
        val saved = Placeholders.resolver
        try {
            Placeholders.resolver = { _, text -> text.replace("%min%", "3") }
            assertTrue(PlayerConditions.within(ctx, 3.5, "%min%", null))
            assertFalse(PlayerConditions.within(ctx, 2.0, "%min%", null))
        } finally {
            Placeholders.resolver = saved
        }
    }

    @Test
    fun cooldownVariablesAreNamedSafely() {
        assertEquals("cooldown_daily", PlayerConditions.variable("daily"))
        assertEquals("cooldown_a_b_c", PlayerConditions.variable(" a b:c "))
    }

    @Test
    fun conditionsCheckTheirSettings() {
        assertFailsWith<IllegalArgumentException> { PlayerConditions.FreeSlots(37) }
        assertFailsWith<IllegalArgumentException> { PlayerConditions.HeldItem(amount = 0) }
        assertFailsWith<IllegalArgumentException> { PlayerConditions.HeldItem(hand = EquipmentSlot.HEAD) }
        assertFailsWith<IllegalArgumentException> { PlayerConditions.Gamemode([]) }
        assertFailsWith<IllegalArgumentException> { PlayerConditions.World([]) }
        assertFailsWith<IllegalArgumentException> { PlayerConditions.Near("world", 0.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Social.Cooldown(" ") }
        assertFailsWith<IllegalArgumentException> { Social.Cooldown("k", duration = kotlin.time.Duration.ZERO) }
    }

    @Test
    fun theNewConditionsAreReadAndWrittenBack() {
        val text = """
            title = "T"
            items.a {
              material = stone
              slot = 0
              view.require = [
                { condition { type = and, conditions = [ { type = free-slots, min = 3 }, { type = health, min = "10" } ] } }
                { condition { type = or, conditions = [ { type = gamemode, modes = [SURVIVAL, ADVENTURE] }, { type = world, names = [world, spawn] } ] } }
                { condition { type = held-item, material = diamond_sword, hand = OFF_HAND } }
                { condition { type = one-of, conditions = [ { type = online, player = Steve }, { type = chance, percent = 50 } ] } }
                { condition { type = at-least, count = 2, conditions = [ { type = food, max = "5" }, { type = level, min = "3" } ] } }
                { condition { type = group, name = vip } }
                { condition { type = variable-exists, name = seen, scope = PLAYER } }
                { condition { type = cooldown, key = daily } }
                { condition { type = experience, min = "10" } }
                { condition { type = online-count, max = "50" } }
                { condition { type = region, names = [spawn, "{arg}"] } }
                { condition { type = near, world = world, x = 1.5, y = 64, z = -2, distance = 10 } }
              ]
            }
        """.trimIndent()
        val menu = Registry.format.decode(text)
        val conditions = menu.items.getValue("a").view!!.require.map { it.condition }
        assertEquals(12, conditions.size)
        assertEquals(Logic.And([PlayerConditions.FreeSlots(3), PlayerConditions.Health(min = "10")]), conditions[0])
        assertEquals(PlayerConditions.Gamemode([GameMode.SURVIVAL, GameMode.ADVENTURE]), (conditions[1] as Logic.Or).conditions[0])
        assertEquals(EquipmentSlot.OFF_HAND, (conditions[2] as PlayerConditions.HeldItem).hand)
        assertEquals(PlayerConditions.Cooldown("daily"), conditions[7])
        assertEquals(PlayerConditions.Region(["spawn", "{arg}"]), conditions[10])
        assertEquals(PlayerConditions.Near("world", 1.5, 64.0, -2.0, 10.0), conditions[11])
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])))
    }

    @Test
    fun aGateExplainsEachCheck() {
        val gate = net.xalitoria.menu.model.Gate(
            require = [
                net.xalitoria.menu.model.Check(yes),
                net.xalitoria.menu.model.Check(Logic.And([]), optional = true),
                net.xalitoria.menu.model.Check(Logic.Chance(0.0)),
            ],
        )
        val lines = gate.explain(ctx)
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("holds: "), lines[0])
        assertTrue(lines[2].startsWith("FAILS: ") && "Chance" in lines[2], lines[2])
        assertEquals(emptyList(), (null as net.xalitoria.menu.model.Gate?).explain(ctx))
    }
}
