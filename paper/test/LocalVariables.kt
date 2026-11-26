import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mc.xalitoria.menu.paper.config.MainConfig.StoreKind
import mc.xalitoria.menu.paper.config.MainConfig.Variables.Rule
import mc.xalitoria.menu.paper.variable.Shard
import mc.xalitoria.menu.paper.variable.SqlBackend
import mc.xalitoria.menu.paper.variable.Storage
import mc.xalitoria.menu.paper.variable.StoreRules
import mc.xalitoria.menu.paper.variable.VariableStore
import net.xalitoria.menu.Scope
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The variables of one server: memory and the SQLite file. */
class LocalVariables {
    private lateinit var directory: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger.getLogger("test")
    private val player = Scope.Player(UUID.fromString("00000000-0000-0000-0000-000000000042"))

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("xalimenu-vars")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        directory.toFile().deleteRecursively()
    }

    private fun storage(name: String = "test.db"): Storage {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${directory.resolve(name)}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }
        return Storage.of(HikariDataSource(config), "t_")
    }

    private fun shard(storage: Storage) = Shard("local", scope, logger, connect = { storage.attach(); SqlBackend(storage) }).also { assertTrue(it.openNow()) }

    private fun store(storage: Storage, rules: StoreRules = StoreRules(StoreKind.LOCAL, emptyList())) =
        VariableStore(rules, Shard("memory", scope, logger), shard(storage), null)

    @Test
    fun globalAndPersonalAreApart() {
        val store = store(storage())
        assertTrue(store.set(Scope.Global, "Coins", "5"))
        assertTrue(store.set(player, "coins", "9"))
        assertEquals("5", store[Scope.Global, "COINS"]?.value, "names ignore case")
        assertEquals("9", store[player, "coins"]?.value)
        assertNull(store[Scope.Player(UUID.randomUUID()), "coins"])
    }

    @Test
    fun replaceFalseKeepsWhatIsThere() {
        val store = store(storage())
        store.set(Scope.Global, "a", "1")
        assertFalse(store.set(Scope.Global, "a", "2", null, false))
        assertEquals("1", store[Scope.Global, "a"]?.value)
        assertTrue(store.set(Scope.Global, "a", "3"))
        assertEquals("3", store[Scope.Global, "a"]?.value)
    }

    @Test
    fun expiredVariablesAreGone() {
        val store = store(storage())
        assertFalse(store.set(Scope.Global, "old", "x", Instant.now().minusSeconds(5), true), "already expired is not written")
        assertTrue(store.set(Scope.Global, "soon", "x", Instant.now().plusSeconds(60), true))
        assertEquals("x", store[Scope.Global, "soon"]?.value)
        assertTrue(store.set(Scope.Global, "gone", "x", Instant.now().plusMillis(30), true))
        Thread.sleep(80)
        assertNull(store[Scope.Global, "gone"])
        assertTrue(store.set(Scope.Global, "gone", "again"), "an expired one can be set again without replace")
    }

    @Test
    fun numbersChange() {
        val store = store(storage())
        assertTrue(store.change(Scope.Global, "n") { it + BigDecimal(5) })
        assertEquals("5", store[Scope.Global, "n"]?.value, "a missing variable counts as 0, and whole numbers have no decimals")
        store.change(Scope.Global, "n") { it.divide(BigDecimal(3), 8, RoundingMode.HALF_UP) }
        assertEquals("1.66666667", store[Scope.Global, "n"]?.value)
        store.set(Scope.Global, "text", "hello")
        assertFalse(store.change(Scope.Global, "text") { it + BigDecimal.ONE })
        assertEquals("hello", store[Scope.Global, "text"]?.value)
        assertFalse(store.change(Scope.Global, "n") { it.divide(BigDecimal.ZERO) }, "a change that can't be made is refused")
        assertEquals("1.66666667", store[Scope.Global, "n"]?.value)
    }

    @Test
    fun smallAmountsAreNotLost() {
        val store = store(storage())
        repeat(3) { store.change(Scope.Global, "x") { it + BigDecimal("0.005") } }
        assertEquals("0.015", store[Scope.Global, "x"]?.value)
        store.set(Scope.Global, "big", "9007199254740993")
        store.change(Scope.Global, "big") { it + BigDecimal.ONE }
        assertEquals("9007199254740994", store[Scope.Global, "big"]?.value, "past what a double holds")
        store.set(Scope.Global, "huge", "1e999999999")
        assertFalse(store.change(Scope.Global, "huge") { it + BigDecimal.ONE }, "an exponent is text, not a number")
    }

    @Test
    fun changingKeepsTheExpiry() {
        val store = store(storage())
        val until = Instant.now().plusSeconds(600)
        store.set(player, "streak", "1", until, true)
        store.change(player, "streak") { it + BigDecimal.ONE }
        assertEquals(until.toEpochMilli(), store[player, "streak"]?.expiry?.toEpochMilli())
        assertEquals("2", store[player, "streak"]?.value)
    }

    @Test
    fun removing() {
        val store = store(storage())
        store.set(Scope.Global, "a", "1")
        assertTrue(store.remove(Scope.Global, "a"))
        assertFalse(store.remove(Scope.Global, "a"))
        assertNull(store[Scope.Global, "a"])
    }

    @Test
    fun theyOutliveTheStore() {
        val storage = storage()
        val first = store(storage)
        first.set(Scope.Global, "kept", "yes")
        first.set(player, "mine", "1", Instant.now().plusSeconds(600), true)
        first.set(Scope.Global, "removed", "x")
        first.remove(Scope.Global, "removed")
        first.set(Scope.Global, "short", "x", Instant.now().plusMillis(50), true)
        runBlocking { first.idle() }
        // The pool is the next store's too, so only the writes are finished, not the pool closed.
        Thread.sleep(100)
        val second = store(storage)
        assertEquals("yes", second[Scope.Global, "kept"]?.value)
        assertEquals("1", second[player, "mine"]?.value)
        assertNull(second[Scope.Global, "removed"])
        assertNull(second[Scope.Global, "short"])
        assertEquals(listOf("kept"), second.names(Scope.Global))
    }

    @Test
    fun rulesChooseTheStore() {
        val rules = StoreRules(StoreKind.LOCAL, listOf(Rule("tmp_*", StoreKind.MEMORY), Rule("Net_?", StoreKind.NETWORK), Rule("*", StoreKind.MEMORY)))
        assertEquals(StoreKind.MEMORY, rules.of("TMP_anything"))
        assertEquals(StoreKind.NETWORK, rules.of("net_a"))
        assertEquals(StoreKind.MEMORY, rules.of("net_ab"), "? is one character; the catch-all comes next")
        assertEquals(StoreKind.MEMORY, rules.of("a.b(c)"), "regex characters are only text")
        assertEquals(StoreKind.LOCAL, StoreRules(StoreKind.LOCAL, listOf(Rule("a.c", StoreKind.MEMORY))).of("abc"))
    }

    @Test
    fun memoryVariablesAreNotKept() {
        val storage = storage()
        val rules = StoreRules(StoreKind.LOCAL, listOf(Rule("tmp_*", StoreKind.MEMORY)))
        val first = store(storage, rules)
        first.set(Scope.Global, "tmp_a", "1")
        first.set(Scope.Global, "keep", "2")
        assertEquals("1", first[Scope.Global, "tmp_a"]?.value)
        runBlocking { first.idle() }
        val second = store(storage, rules)
        assertNull(second[Scope.Global, "tmp_a"])
        assertEquals("2", second[Scope.Global, "keep"]?.value)
    }

    @Test
    fun withoutANetworkStoreItsVariablesAreLocal() {
        val store = store(storage(), StoreRules(StoreKind.NETWORK, emptyList()))
        assertTrue(store.isAvailable("anything"))
        assertTrue(store.set(Scope.Global, "x", "1"))
        assertEquals("1", store[Scope.Global, "x"]?.value)
    }

    @Test
    fun variableShardEnforcesKeyValueAndPerOwnerLimits() {
        val shard = Shard(
            name = "bounded",
            scope = scope,
            logger = logger,
            maxKeyLength = 8,
            maxValueLength = 10,
            maxEntriesPerOwner = 2,
            maxTotalEntries = 3,
        )
        val store = VariableStore(StoreRules(StoreKind.MEMORY, emptyList()), shard, shard, null)

        assertFalse(store.set(player, "   ", "1"), "blank key is rejected")
        assertFalse(store.set(player, "toolongkey", "1"), "oversized key is rejected")
        assertFalse(store.set(player, "ok", "01234567890"), "oversized value is rejected")

        assertTrue(store.set(player, "a", "1"))
        assertTrue(store.set(player, "b", "2"))
        assertFalse(store.set(player, "c", "3"), "per-owner cap blocks unbounded key creation")
        assertTrue(store.set(player, "a", "updated"), "updating an existing key is still allowed at the cap")

        val other = Scope.Player(UUID.randomUUID())
        assertTrue(store.set(other, "a", "1"))
        assertFalse(store.set(other, "b", "2"), "total cap blocks unbounded growth across owners")

        assertTrue(store.set(player, "a", "9999999999"))
        assertFalse(store.change(player, "a") { it * BigDecimal("10") }, "numeric growth past maxValueLength is rejected")
    }
}
