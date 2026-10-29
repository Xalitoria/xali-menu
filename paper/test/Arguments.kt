import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.MenuArgs
import mc.xalitoria.menu.paper.runtime.Placeholders
import mc.xalitoria.util.message.PLAIN
import org.bukkit.entity.Player

/** What players type after a menu's command is theirs, not the menu's. */
class Arguments {
    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) = Unit
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
    }

    private fun ctx(vararg args: Pair<String, String>): Context {
        Placeholders.resolver = { _, text -> text.replace("%secret%", "TOPSECRET") }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player
        return Context(player, "test", mapOf(*args), host)
    }

    @AfterTest
    fun restore() {
        Placeholders.resolver = { _, text -> text }
    }

    @Test
    fun wordsFillTheNamesOneEach() {
        assertEquals(mapOf("player" to "Steve", "amount" to "5"), MenuArgs.bind(["player", "amount"], ["Steve", "5"]))
        assertNull(MenuArgs.bind(["player", "amount"], ["Steve"]), "too few")
        assertNull(MenuArgs.bind(["player"], ["Steve", "10"]), "an unmarked name can't take a second word")
        assertEquals(emptyMap(), MenuArgs.bind([], ["any", "words"]), "a menu without arguments ignores them")
    }

    @Test
    fun aMarkedLastNameTakesTheRest() {
        assertEquals(mapOf("player" to "Steve", "reason" to "being very rude"), MenuArgs.bind(["player", "reason..."], ["Steve", "being", "very", "rude"]))
        assertEquals(mapOf("player" to "Steve", "reason" to "ok"), MenuArgs.bind(["player", "reason..."], ["Steve", "ok"]))
        assertNull(MenuArgs.bind(["player", "reason..."], ["Steve"]))
        assertEquals("<player> <reason...>", MenuArgs.usage(["player", "reason..."]))
    }

    @Test
    fun badArgumentLists() {
        assertTrue(MenuArgs.problems(["a", "b..."]).isEmpty())
        assertTrue(MenuArgs.problems(["a...", "b"]).isNotEmpty(), "only the last can take the rest")
        assertTrue(MenuArgs.problems(["a", "a"]).isNotEmpty())
        assertTrue(MenuArgs.problems(["a b"]).isNotEmpty())
        assertTrue(MenuArgs.problems(["{a}"]).isNotEmpty())
    }

    @Test
    fun anArgumentCannotStartAPlaceholderOrBreakALine() {
        val ctx = ctx("x" to "a%secret%b\nsay hi\u0000")
        assertEquals("give asecretbsay hi", ctx.parse("give {x}"), "no % to start a placeholder, no line break")
        assertEquals("TOPSECRET", ctx.parse("%secret%"), "the menu's own placeholders still work")
    }

    @Test
    fun anArgumentIsNotReadAsAnotherArgument() {
        val ctx = ctx("a" to "{b}", "b" to "z")
        assertEquals("{b} z", ctx.parse("{a} {b}"))
        assertEquals("{unknown}", ctx.parse("{unknown}"), "text that is not an argument stays as it is")
    }

    @Test
    fun anArgumentShowsAsTypedInText() {
        val ctx = ctx("x" to "<red>boom</red> &cred <click:run_command:/op me>click</click>")
        val shown = PLAIN.serialize(ctx.component("hi {x}"))
        assertEquals("hi <red>boom</red> red <click:run_command:/op me>click</click>", shown, "tags are text, and color codes are taken out")
    }

    @Test
    fun anArgumentInJsonStaysAString() {
        val ctx = ctx("x" to "a\"},{\"text\":\"b\\")
        val json = ctx.parse("""{"text":"{x}"}""") { it.replace("\\", "\\\\").replace("\"", "\\\"") }
        val parsed = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json)
        assertEquals("a\"},{\"text\":\"b\\", PLAIN.serialize(parsed))
    }
}
