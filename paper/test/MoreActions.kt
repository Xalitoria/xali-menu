import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.xalitoria.menu.Action
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.MenuContext
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.action.Effects
import mc.xalitoria.menu.paper.action.Flow
import mc.xalitoria.menu.paper.action.Inventory
import mc.xalitoria.menu.paper.action.Social
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.runtime.Clients
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.Prompt
import mc.xalitoria.menu.paper.runtime.runActions
import org.bukkit.entity.Player

class MoreActions {
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

    /** Notes what `{name}` and `{index}` are when it runs. */
    private val seen = object : Action {
        override fun run(ctx: MenuContext) {
            log += ctx.parse("{index}={value}")
        }
    }

    // Flow.

    @Test
    fun allRunsItsActionsAndAStopInItDoesNotEndTheOuterList() {
        ctx.run([Flow.All([step("a"), Actions.Stop, step("hidden")]), step("b")])
        assertEquals(["a", "b"], log)
    }

    @Test
    fun randomPicksTheNumberAskedForWithoutRepeats() {
        val pool = (1..5).map { step("s$it") }
        ctx.run([Flow.Pick(pool, count = 3)])
        assertEquals(3, log.size)
        assertEquals(3, log.toSet().size)
        log.clear()
        ctx.run([Flow.Pick(pool, count = 9)])
        assertEquals(5, log.toSet().size, "asking for more than there are runs them all")
        assertFailsWith<IllegalArgumentException> { Flow.Pick([]) }
        assertFailsWith<IllegalArgumentException> { Flow.Pick(pool, count = 0) }
    }

    @Test
    fun runForRunsOnceForEveryElementWithItsValues() {
        ctx.run([Flow.RunFor(Catalogs.Values(list = ["x", "y", "z"]), [seen])])
        assertEquals(["1=x", "2=y", "3=z"], log)
    }

    @Test
    fun runForStopsAtItsLimitAndEachRunStandsAlone() {
        ctx.run([Flow.RunFor(Catalogs.Range("1", "10"), [Actions.Stop, seen], limit = 2), step("after")])
        assertEquals(["after"], log, "a stop ends one element's run, not the whole")
        log.clear()
        ctx.run([Flow.RunFor(Catalogs.Range("1", "10"), [seen], limit = 2)])
        assertEquals(["1=1", "2=2"], log)
        assertFailsWith<IllegalArgumentException> { Flow.RunFor(Catalogs.Worlds, [], limit = 0) }
    }

    @Test
    fun runForSeesCatalogsOfAddons() {
        val catalog = object : Catalog {
            override fun elements(ctx: MenuContext) = listOf(mapOf("value" to "home"))
        }
        ctx.run([Flow.RunFor(catalog, [seen])])
        assertEquals(["1=home"], log)
    }

    // Inventory.

    @Test
    fun takingSpreadsOverStacksInOrder() {
        assertEquals([5, 3, 0], Inventory.removals([5, 10, 7], 8))
        assertEquals([5, 10, 7], Inventory.removals([5, 10, 7], 22))
        assertEquals([0, 0], Inventory.removals([5, 5], 0))
        assertNull(Inventory.removals([5, 5], 11), "too few")
        assertNull(Inventory.removals([], 1))
    }

    @Test
    fun takeItemNeedsAnAmount() {
        assertFailsWith<IllegalArgumentException> { Inventory.TakeItem(amount = 0) }
    }

    // Effects.

    @Test
    fun aChangeIsHeldInRange() {
        assertEquals(20.0, Effects.Change.SET.apply(5.0, 99.0, 20.0))
        assertEquals(8.0, Effects.Change.ADD.apply(5.0, 3.0, 20.0))
        assertEquals(20.0, Effects.Change.ADD.apply(18.0, 5.0, 20.0))
        assertEquals(0.0, Effects.Change.TAKE.apply(2.0, 5.0, 20.0))
        assertEquals(0.0, Effects.Change.SET.apply(5.0, -3.0, 20.0))
    }

    @Test
    fun coordinatesAreAbsoluteOrRelative() {
        assertEquals(12.5, Effects.coordinate("12.5", 100.0))
        assertEquals(100.0, Effects.coordinate("~", 100.0))
        assertEquals(105.0, Effects.coordinate("~5", 100.0))
        assertEquals(97.5, Effects.coordinate("~-2.5", 100.0))
        assertEquals(-3.0, Effects.coordinate(" -3 ", 100.0))
        assertNull(Effects.coordinate("abc", 1.0))
        assertNull(Effects.coordinate("~x", 1.0))
        assertNull(Effects.coordinate("NaN", 1.0))
        assertNull(Effects.coordinate("", 1.0))
    }

    @Test
    fun effectsCheckTheirSettings() {
        assertFailsWith<IllegalArgumentException> { Effects.Potion("speed", amplifier = 256) }
        assertFailsWith<IllegalArgumentException> { Effects.ShowTitle() }
        assertFailsWith<IllegalArgumentException> { Effects.ShowTitle("x", stay = -1) }
        assertFailsWith<IllegalArgumentException> { Effects.ShowBossBar("x", progress = 2f) }
        assertFailsWith<IllegalArgumentException> { Effects.OpenBook([]) }
        assertFailsWith<IllegalArgumentException> { Effects.ShowParticle("flame", count = 0) }
    }

    // Social.

    private fun prompt(match: String? = null) = Prompt(ctx, [step("then")], [step("cancelled")], "cancel", match?.let(::Regex), null)

    @Test
    fun aReplyIsAcceptedCancelledOrTurnedDown() {
        assertEquals(Prompt.Verdict.ACCEPT, prompt().verdict("hello there"))
        assertEquals(Prompt.Verdict.CANCEL, prompt().verdict("  Cancel "))
        assertEquals(Prompt.Verdict.ACCEPT, prompt("[0-9]+").verdict(" 42 "))
        assertEquals(Prompt.Verdict.INVALID, prompt("[0-9]+").verdict("4 2"))
        assertEquals(Prompt.Verdict.CANCEL, prompt("[0-9]+").verdict("cancel"), "cancelling doesn't have to fit")
    }

    @Test
    fun aReplyLongerThanTheLimitIsTurnedDown() {
        val short = Prompt(ctx, [step("then")], [step("cancelled")], "cancel", null, null, maxLength = 5)
        assertEquals(Prompt.Verdict.ACCEPT, short.verdict(" abcde "), "the limit counts what is left after trimming")
        assertEquals(Prompt.Verdict.TOO_LONG, short.verdict("abcdef"))
        assertEquals(true, Prompt.Verdict.TOO_LONG.retry)
        assertEquals(false, Prompt.Verdict.CANCEL.retry)
        assertEquals("&cThat is longer than 5 characters.", short.complaint(Prompt.Verdict.TOO_LONG), "said even when no invalid text is set")
        val translated = mc.xalitoria.menu.paper.config.MessageConfig.Input(tooLong = "Zu lang: {0}")
        assertEquals("Zu lang: 5", short.complaint(Prompt.Verdict.TOO_LONG, translated), "the text comes from the language file")
        assertEquals(null, short.complaint(Prompt.Verdict.INVALID))
        assertEquals(Prompt.Verdict.CANCEL, short.verdict("cancel"), "cancelling doesn't have to fit")
    }

    @Test
    fun inputChecksItsSettings() {
        assertFailsWith<IllegalArgumentException> { Social.Input("?", []) }
        assertFailsWith<IllegalArgumentException> { Social.Input("?", [Actions.Stop], cancel = " ") }
        assertFailsWith<Exception> { Social.Input("?", [Actions.Stop], match = "(") }
        assertFailsWith<IllegalArgumentException> { Social.Input("?", [Actions.Stop], maxLength = 0) }
        assertFailsWith<IllegalArgumentException> { Social.Input("?", [Actions.Stop], maxLength = 5000) }
        assertEquals(Social.Input.Mode.AUTO, Social.Input("?", [Actions.Stop]).mode, "a dialog or an anvil unless said otherwise")
    }

    @Test
    fun aClientNeedsAtLeastMinecraft1216ForADialog() {
        val player = ctx.player
        val was = Clients.versionOf
        try {
            Clients.versionOf = { null }
            assertEquals(true, Clients.dialogs(player), "nothing says the client is older than the server")
            Clients.versionOf = { 771 }
            assertEquals(true, Clients.dialogs(player))
            Clients.versionOf = { 770 }
            assertEquals(false, Clients.dialogs(player), "1.21.5 has no dialogs")
            Clients.versionOf = { 47 }
            assertEquals(false, Clients.dialogs(player))
        } finally {
            Clients.versionOf = was
        }
    }

    @Test
    fun aValueIsOneArgumentOfACommand() {
        assertEquals("gold", Social.quote("gold"))
        assertEquals("\"two words\"", Social.quote("two words"))
        assertEquals("\"\"", Social.quote(""))
        assertEquals("\"a b\"", Social.quote("\"a b\""))
        assertEquals("ab", Social.quote("a\nb"), "a line break can't start another command")
    }

    // The menu file.

    @Test
    fun theNewActionsAreRead() {
        val menu = Registry.format.decode(
            """
            title = "T"
            items.a {
              material = stone
              slot = 0
              click.any.actions = [
                { type = give-item, item { material = diamond, amount = 2, name = "&bGem" }, overflow = ABORT }
                { type = take-item, amount = 3 }
                { type = clear-items }
                { type = potion, effect = speed, duration = 10s, amplifier = 1 }
                { type = teleport, x = "~", y = "64", z = "{z}", world = world_nether }
                { type = gamemode, mode = creative }
                { type = health, amount = 4, change = ADD }
                { type = food, amount = 20, saturation = 5 }
                { type = level, amount = 2, take = true }
                { type = title, title = "&aHi", subtitle = "there", fade-in = 5 }
                { type = boss-bar, text = "Boss", color = RED, style = NOTCHED_10, progress = 0.5, duration = 3s }
                { type = book, pages = ["one", "two"], title = T, author = A }
                { type = particle, particle = flame, count = 5, everyone = true }
                { type = input, prompt = "Name?", then = [ { type = message, text = "{input}" } ], match = "[a-z]+", invalid = "no", timeout = 1m, mode = ANVIL, title = "Home", initial = "home", submit = "Save", max-length = 20 }
                { type = group, name = vip }
                { type = meta, key = prefix, value = "[V]" }
                { type = all, actions = [ { type = close } ] }
                { type = random, actions = [ { type = close }, { type = refresh } ], count = 2 }
                { type = run-for, catalog { type = players }, actions = [ { type = close } ], limit = 5 }
                { type = log, text = "hi", level = WARNING }
                { type = skin, skin = "Notch", slim = true }
                { type = skin-reset }
              ]
            }
            """.trimIndent()
        )
        val actions = menu.items.getValue("a").click.any!!.actions
        assertEquals(22, actions.size)
        assertEquals(Inventory.Overflow.ABORT, (actions[0] as Inventory.GiveItem).overflow)
        assertEquals(2, (actions[0] as Inventory.GiveItem).item.amount)
        assertEquals(10.seconds, (actions[3] as Effects.Potion).duration)
        assertEquals(Effects.Change.ADD, (actions[6] as Effects.Health).change)
        assertEquals(5, (actions[9] as Effects.ShowTitle).fadeIn)
        assertEquals(1.minutes, (actions[13] as Social.Input).timeout)
        assertEquals(
            listOf(Social.Input.Mode.ANVIL, "Home", "home", "Save", 20),
            (actions[13] as Social.Input).let { listOf(it.mode, it.title, it.initial, it.submit, it.maxLength) },
        )
        assertEquals(Catalogs.Players(), (actions[18] as Flow.RunFor).catalog)
        assertEquals(Effects.Skin("Notch", slim = true), actions[20])
        assertEquals(Effects.SkinReset, actions[21])
    }

    @Test
    fun theNewActionsAreWrittenAndReadBack() {
        val actions = listOf(
            Inventory.GiveItem(net.xalitoria.menu.model.Item("diamond", amount = 3)),
            Effects.Potion("speed", 20.seconds, 2, remove = false),
            Effects.Teleport(y = "70"),
            Effects.Health("5", Effects.Change.TAKE),
            Flow.RunFor(Catalogs.Range("1", "4"), [Flow.All([Actions.Close])], limit = 3),
            Social.Input("?", [Actions.Close], onCancel = [Actions.Refresh], match = "x+", mode = Social.Input.Mode.CHAT, initial = "x"),
            Effects.Skin("{arg}"),
            Effects.SkinReset,
        )
        val menu = net.xalitoria.menu.model.Menu(
            title = "t",
            items = mapOf("a" to net.xalitoria.menu.model.Item("stone", slot = 0, click = net.xalitoria.menu.model.Clicks(any = net.xalitoria.menu.model.Handler(actions)))),
        )
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])))
    }
}
