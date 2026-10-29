import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.api.model.Check
import mc.xalitoria.menu.api.model.Gate
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.Placeholders
import mc.xalitoria.menu.paper.runtime.passes
import org.bukkit.entity.Player

class Gates {
    private val ran = mutableListOf<Action>()

    /** Records the actions a gate runs instead of running them. */
    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) {
            ran += actions
        }

        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
    }

    private fun player(permissions: Set<String> = emptySet()): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            if (method.name == "hasPermission") args[0] in permissions else null
        } as Player

    private fun ctx(vararg placeholders: Pair<String, String>, player: Player = player(), args: Map<String, String> = emptyMap()): Context {
        val values = mapOf(*placeholders)
        Placeholders.resolver = { _, text -> values.entries.fold(text) { acc, entry -> acc.replace(entry.key, entry.value) } }
        return Context(player, "test", args, host)
    }

    @AfterTest
    fun clear() = ran.clear()

    private fun check(condition: Condition, deny: List<Action> = [], pass: List<Action> = [], optional: Boolean = false) =
        Check(condition, optional, pass, deny)

    @Test
    fun conditions() {
        val ctx = ctx("%level%" to "12", "%rank%" to "VIP", "%name%" to "Steve", player = player(setOf("shop.use")))
        fun holds(condition: Condition) = condition.test(ctx)

        assertTrue(holds(Conditions.Permission("shop.use")))
        assertFalse(holds(Conditions.Permission("shop.admin")))
        assertTrue(holds(Conditions.Compare("%level%", Conditions.Compare.Op.GTE, "12")))
        assertFalse(holds(Conditions.Compare("%level%", Conditions.Compare.Op.LT, "12")))
        assertTrue(holds(Conditions.Compare("%level%", Conditions.Compare.Op.NEQ, "11")))
        assertFalse(holds(Conditions.Compare("%rank%", Conditions.Compare.Op.GT, "1")), "not a number never holds")
        assertTrue(holds(Conditions.Text("%rank%", Conditions.Text.Op.EQUALS_IGNORE_CASE, "vip")))
        assertFalse(holds(Conditions.Text("%rank%", Conditions.Text.Op.EQUALS, "vip")))
        assertTrue(holds(Conditions.Text("%name%", Conditions.Text.Op.CONTAINS, "tev")))
        assertTrue(holds(Conditions.Regex("%name%", "[A-Z][a-z]+")))
        assertFalse(holds(Conditions.Regex("%name%", "[a-z]+")))
        assertFalse(holds(Conditions.Regex("%name%", "(")), "a broken pattern never holds")
        assertTrue(holds(Conditions.Not(Conditions.Permission("shop.admin"))))
        assertFalse(holds(Conditions.Never))
        assertFalse(holds(Conditions.Money("1")), "no economy, no money")
    }

    @Test
    fun menuArgumentsFillText() {
        val ctx = ctx(args = mapOf("target" to "Alex"), player = player(setOf("kick.Alex")))
        assertTrue(Conditions.Permission("kick.{target}").test(ctx))
    }

    @Test
    fun everyRequiredCheckMustHoldAndTheFirstFailureEndsIt() {
        val ctx = ctx(player = player(setOf("a", "c")))
        val gate = Gate(
            [
                check(Conditions.Permission("a"), pass = [Actions.Message("a ok")]),
                check(Conditions.Permission("b"), deny = [Actions.Message("no b")]),
                check(Conditions.Permission("c"), pass = [Actions.Message("c ok")]),
            ],
            deny = [Actions.Close],
        )
        assertFalse(gate.passes(ctx, run = true))
        assertEquals(listOf(Actions.Message("a ok"), Actions.Message("no b"), Actions.Close), ran)
    }

    @Test
    fun optionalChecksDoNotFailTheGate() {
        val ctx = ctx(player = player(setOf("a")))
        val gate = Gate([check(Conditions.Permission("a")), check(Conditions.Permission("b"), optional = true)], pass = [Actions.Refresh])
        assertTrue(gate.passes(ctx, run = true))
        assertEquals(listOf<Action>(Actions.Refresh), ran)
    }

    @Test
    fun aMinimumCountsAnyChecks() {
        val ctx = ctx(player = player(setOf("b", "c")))
        val checks = ["a", "b", "c"].map { check(Conditions.Permission(it), deny = [Actions.Message("no $it")]) }
        assertTrue(Gate(checks, minimum = 2).passes(ctx, run = true))
        assertEquals(listOf<Action>(Actions.Message("no a")), ran, "all are looked at")
        assertFalse(Gate(checks, minimum = 3).passes(ctx, run = false))
    }

    @Test
    fun stopAtSuccessSkipsTheRest() {
        val ctx = ctx(player = player(setOf("a", "b")))
        val gate = Gate(
            [check(Conditions.Permission("a"), pass = [Actions.Message("a")]), check(Conditions.Permission("b"), pass = [Actions.Message("b")])],
            minimum = 1,
            stopAtSuccess = true,
        )
        assertTrue(gate.passes(ctx, run = true))
        assertEquals(listOf<Action>(Actions.Message("a")), ran)
    }

    @Test
    fun decidingWhatToShowRunsNothing() {
        val ctx = ctx()
        assertFalse(Gate([check(Conditions.Never, deny = [Actions.Close])], deny = [Actions.Close]).passes(ctx, run = false))
        assertEquals(emptyList(), ran)
        assertTrue((null as Gate?).passes(ctx, run = true))
    }
}
