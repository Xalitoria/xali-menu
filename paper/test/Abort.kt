import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.runActions
import org.bukkit.entity.Player

class Abort {
    private val log = mutableListOf<String>()

    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) = runActions(actions, ctx) { _, reason -> log += "failed: $reason" }
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
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
