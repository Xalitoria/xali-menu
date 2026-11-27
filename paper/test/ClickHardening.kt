import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.action.Effects
import mc.xalitoria.menu.paper.runtime.Clients
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.runtime.MenuClicks
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActionsAborted
import net.xalitoria.menu.model.Clicks
import net.xalitoria.menu.model.Handler
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure anti-duplication and legacy-client resync rules (DUPLICATION.md F1–F8). */
class ClickHardening {
    private val originalVersionOf = Clients.versionOf

    @AfterTest
    fun restoreClients() {
        Clients.versionOf = originalVersionOf
    }

    private fun proxyPlayer(): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getName" -> "Tester"
                else -> null
            }
        } as Player

    private val noopHost = object : Host {
        override fun run(actions: List<Action>, ctx: Context) = Unit
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
        override fun page(player: Player, relative: Boolean, value: Int) = Unit
    }

    @Test
    fun clickAnyOnlyMatchesTheFiveButtonClicksAndNeverDoubleClickOrStrayKeys() {
        val anyHandler = Handler(actions = listOf(Actions.Close))
        val leftHandler = Handler(actions = listOf(Actions.Refresh))
        val clicks = Clicks(any = anyHandler, left = leftHandler)

        // The five schema button clicks resolve cleanly.
        assertSame(leftHandler, MenuClicks.resolveHandler(clicks, ClickType.LEFT))
        for (button in listOf(ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.MIDDLE)) {
            assertSame(anyHandler, MenuClicks.resolveHandler(clicks, button), "button $button should fall back to any")
        }

        // Non-button click types (double-click, number keys, drops, offhand, creative, border, unknown) never trigger any.
        for (click in ClickType.entries) {
            if (click in MenuClicks.HANDLED_CLICKS) {
                assertNotNull(MenuClicks.resolveHandler(clicks, click), "handled click $click")
            } else {
                assertNull(MenuClicks.resolveHandler(clicks, click), "non-button click $click must not trigger click.any")
            }
        }
    }

    @Test
    fun resyncPolicyCoversLegacyClientsCreativeModeAndSwapDropDoubleClicks() {
        // Pre-1.17 legacy clients (1.8.9..1.16.5 via ViaVersion) resync on every cancelled click.
        assertTrue(MenuClicks.needsResyncAfterCancel(ClickType.LEFT, creative = false, legacyClient = true))
        assertTrue(MenuClicks.needsResyncAfterCancel(ClickType.RIGHT, creative = false, legacyClient = true))
        assertTrue(MenuClicks.needsResyncAfterCancel(ClickType.SHIFT_LEFT, creative = false, legacyClient = true))

        // Creative mode resyncs on every cancelled click.
        assertTrue(MenuClicks.needsResyncAfterCancel(ClickType.LEFT, creative = true, legacyClient = false))
        assertTrue(MenuClicks.needsResyncAfterCancel(ClickType.MIDDLE, creative = true, legacyClient = false))

        // Modern survival clients skip redundant resyncs on plain left/right/shift clicks, but resync on swaps, drops and double-clicks.
        assertFalse(MenuClicks.needsResyncAfterCancel(ClickType.LEFT, creative = false, legacyClient = false))
        assertFalse(MenuClicks.needsResyncAfterCancel(ClickType.RIGHT, creative = false, legacyClient = false))
        assertFalse(MenuClicks.needsResyncAfterCancel(ClickType.SHIFT_LEFT, creative = false, legacyClient = false))
        for (risky in listOf(ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.DOUBLE_CLICK, ClickType.CREATIVE, ClickType.MIDDLE, ClickType.UNKNOWN)) {
            assertTrue(MenuClicks.needsResyncAfterCancel(risky, creative = false, legacyClient = false), "expected resync for $risky")
        }
    }

    @Test
    fun legacyInventoryClientDetectsPre117Protocols() {
        val player = proxyPlayer()
        Clients.versionOf = { 47 } // 1.8.9
        assertTrue(Clients.isLegacyInventoryClient(player))
        Clients.versionOf = { 754 } // 1.16.5
        assertTrue(Clients.isLegacyInventoryClient(player))
        Clients.versionOf = { 755 } // 1.17
        assertFalse(Clients.isLegacyInventoryClient(player))
        Clients.versionOf = { 775 } // 26.x
        assertFalse(Clients.isLegacyInventoryClient(player))
        Clients.versionOf = { null } // native / no ViaVersion
        assertFalse(Clients.isLegacyInventoryClient(player))
    }

    @Test
    fun positiveNumericParsersAndActionsRejectNegativeZeroAndNonFiniteAmounts() {
        assertEquals(10, MenuClicks.positiveInt(" 10 "))
        assertNull(MenuClicks.positiveInt("0"))
        assertNull(MenuClicks.positiveInt("-50"))
        assertNull(MenuClicks.positiveInt("abc"))

        assertEquals(BigDecimal("12.50"), MenuClicks.positiveDecimal("12.50"))
        assertNull(MenuClicks.positiveDecimal("0"))
        assertNull(MenuClicks.positiveDecimal("-100"))
        assertNull(MenuClicks.positiveDecimal("not-a-number"))

        assertEquals(0.0, MenuClicks.nonNegativeDouble("0"))
        assertEquals(5.5, MenuClicks.nonNegativeDouble("5.5"))
        assertNull(MenuClicks.nonNegativeDouble("-1"))
        assertNull(MenuClicks.nonNegativeDouble("NaN"))
        assertNull(MenuClicks.nonNegativeDouble("Infinity"))

        val ctx = Context(proxyPlayer(), "shop", mapOf("n" to "-50"), noopHost)
        assertFailsWith<ActionsAborted> { Actions.Money("{n}", take = true).run(ctx) }
        assertFailsWith<ActionsAborted> { Actions.Money("0", take = true).run(ctx) }
        assertFailsWith<ActionsAborted> { Actions.Experience("{n}", take = true).run(ctx) }
        assertFailsWith<ActionsAborted> { Effects.Levels("{n}", take = true).run(ctx) }
        assertFailsWith<ActionsAborted> { Effects.Health("{n}", Effects.Change.TAKE).run(ctx) }
        assertFailsWith<ActionsAborted> { Effects.Food("NaN", Effects.Change.ADD).run(ctx) }
    }

    @Test
    fun safeContainerTypesExcludeVanillaCraftingAndResultWindows() {
        for (safe in listOf("chest", "HOPPER", "dispenser", "dropper", "shulker_box", "barrel")) {
            assertTrue(MenuClicks.isSafeContainerType(safe), "$safe should be allowed")
        }
        for (unsafe in listOf("workbench", "crafting", "anvil", "smithing", "grindstone", "stonecutter", "cartography", "loom")) {
            assertFalse(MenuClicks.isSafeContainerType(unsafe), "$unsafe has vanilla slot logic and should warn")
        }
    }
}
