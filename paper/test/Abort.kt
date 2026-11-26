import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.runActions
import net.xalitoria.menu.Action
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.Scope
import net.xalitoria.menu.Variable
import net.xalitoria.menu.Variables
import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Instant
import java.util.function.UnaryOperator
import kotlin.test.Test
import kotlin.test.assertEquals

class Abort {
    private val log = mutableListOf<String>()

    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) { runActions(actions, ctx) { _, reason -> log += "failed: $reason" } }
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
        override fun page(player: Player, relative: Boolean, value: Int) = Unit
    }

    private val ctx = Context(Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player, "test", emptyMap(), host)

    private fun step(name: String) = object : Action {
        override fun run(ctx: MenuContext) {
            log += name
        }
    }

    private val boom = object : Action {
        override fun run(ctx: MenuContext) = error("boom")
    }

    @Test
    fun stopEndsTheList() {
        ctx.run(listOf(step("a"), Actions.Stop, step("b")))
        assertEquals(listOf("a"), log)
    }

    @Test
    fun theRunSaysWhetherEveryActionRanToItsEnd() {
        val failed: (Action, String?) -> Unit = { _, _ -> }
        assertEquals(true, runActions(listOf(step("a"), step("b")), ctx, failed))
        assertEquals(true, runActions(emptyList(), ctx, failed))
        assertEquals(false, runActions(listOf(step("a"), Actions.Stop, step("b")), ctx, failed), "an abort is no success")
        assertEquals(false, runActions(listOf(step("a"), boom, step("b")), ctx, failed), "nor is a failure, though the rest ran")
        assertEquals(listOf("a", "b", "a", "a", "b"), log.filterNot { it.startsWith("failed") })
    }

    @Test
    fun aFailingActionDoesNotEndTheList() {
        ctx.run(listOf(step("a"), boom, step("b")))
        assertEquals(listOf("a", "failed: boom", "b"), log)
    }

    @Test
    fun aChanceThatAbortsEndsTheOuterList() {
        ctx.run(listOf(step("a"), Actions.Chance(100.0, Actions.Stop), step("b")))
        assertEquals(listOf("a"), log)
    }

    @Test
    fun aChanceThatMissesRunsNothing() {
        ctx.run(listOf(Actions.Chance(0.0, Actions.Stop), step("b")))
        assertEquals(listOf("b"), log)
    }

    @Test
    fun failedVariableWritesAbortDependentActions() {
        var available = false
        val store = HashMap<String, String>()
        val fakeVars = object : Variables {
            override fun get(scope: Scope, name: String): Variable? = store[name]?.let { Variable(name, it, null) }
            override fun set(scope: Scope, name: String, value: String, expiry: Instant?, replace: Boolean): Boolean {
                if (!available) return false
                if (!replace && name in store) return false
                store[name] = value
                return true
            }
            override fun set(scope: Scope, name: String, value: String): Boolean = set(scope, name, value, null, true)
            override fun remove(scope: Scope, name: String): Boolean = available && store.remove(name) != null
            override fun change(scope: Scope, name: String, change: UnaryOperator<BigDecimal>): Boolean {
                if (!available) return false
                val cur = store[name]?.let(Variable::parseNumber) ?: BigDecimal.ZERO
                store[name] = Variable.format(change.apply(cur))
                return true
            }
            override fun isAvailable(name: String): Boolean = available
        }
        fun varStep(action: Actions.Variable) = object : Action {
            override fun run(ctx: MenuContext) = action.execute(ctx, fakeVars) { log += "unavailable:$it" }
        }

        // Unavailable store must abort dependent actions.
        ctx.run(listOf(varStep(Actions.Variable("claimed", value = "1")), step("reward")))
        assertEquals(listOf("unavailable:claimed"), log)
        log.clear()

        // First set succeeds and runs reward; second set with replace=false fails and aborts reward.
        available = true
        ctx.run(listOf(varStep(Actions.Variable("claimed", value = "1", replace = false)), step("reward")))
        ctx.run(listOf(varStep(Actions.Variable("claimed", value = "1", replace = false)), step("reward")))
        assertEquals(listOf("reward"), log)
        log.clear()

        // Invalid number and division by zero abort dependent actions.
        ctx.run(listOf(varStep(Actions.Variable("n", op = Actions.Variable.Op.INC, value = "abc")), step("reward")))
        ctx.run(listOf(varStep(Actions.Variable("n", op = Actions.Variable.Op.DIV, value = "0")), step("reward")))
        assertEquals(emptyList(), log)
    }
}
