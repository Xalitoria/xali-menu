import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.runActions
import net.xalitoria.menu.Action
import net.xalitoria.menu.MenuContext
import org.bukkit.entity.Player
import java.lang.reflect.Proxy
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
}
