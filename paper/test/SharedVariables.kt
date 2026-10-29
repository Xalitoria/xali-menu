import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mc.xalitoria.menu.api.Scope
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.config.MainConfig.StoreKind
import mc.xalitoria.menu.paper.variable.Shard
import mc.xalitoria.menu.paper.variable.SqlBackend
import mc.xalitoria.menu.paper.variable.Storage
import mc.xalitoria.menu.paper.variable.StoreRules
import mc.xalitoria.menu.paper.variable.VariableStore
import mc.xalitoria.menu.paper.variable.isTransient

/**
 * Several servers on one database, as several stores on one file (and, when `XALIMENU_TEST_MYSQL` holds a JDBC url such as
 * `jdbc:mariadb://localhost:3307/xali?user=root&password=pw` or a `jdbc:mysql:` one, on a real MariaDB too). Looking for changes is done by hand.
 */
class SharedVariables {
    private lateinit var directory: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger.getLogger("test")
    private val rules = StoreRules(StoreKind.NETWORK, emptyList())
    private val storages = mutableListOf<Storage>()

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("xalimenu-shared")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        storages.forEach(Storage::close)
        directory.toFile().deleteRecursively()
    }

    private val mysql: String? = System.getenv("XALIMENU_TEST_MYSQL")
    private var run = System.nanoTime()

    private fun storage(): Storage {
        val config = HikariConfig()
        if (mysql != null) {
            config.jdbcUrl = mysql
            config.driverClassName = if (mysql.startsWith("jdbc:mariadb")) "org.mariadb.jdbc.Driver" else "com.mysql.cj.jdbc.Driver"
            config.maximumPoolSize = 4
        } else {
            config.jdbcUrl = "jdbc:sqlite:${directory.resolve("shared.db")}"
            config.driverClassName = "org.sqlite.JDBC"
            config.maximumPoolSize = 1
        }
        // A prefix of this run's own, so a database that is kept between runs starts empty.
        return Storage.of(HikariDataSource(config), "t${run}_").also { storages += it }
    }

    /** One server: its own pool on the shared database. */
    private fun server(): VariableStore {
        val storage = storage()
        val network = Shard("network", scope, logger, connect = { storage.attach(); SqlBackend(storage) }, pollEvery = null)
        assertTrue(network.openNow())
        return VariableStore(rules, Shard("memory", scope, logger), network, network)
    }

    private fun VariableStore.settle() = runBlocking { idle() }

    @Test
    fun aChangeReachesTheOtherServer() {
        val a = server()
        val b = server()
        a.set(Scope.Global, "motd", "hello")
        a.settle()
        assertNull(b[Scope.Global, "motd"], "not seen until it looks")
        b.poll()
        assertEquals("hello", b[Scope.Global, "motd"]?.value)

        a.set(Scope.Global, "motd", "bye")
        a.settle()
        b.poll()
        assertEquals("bye", b[Scope.Global, "motd"]?.value)
    }

    @Test
    fun removalsReachTheOtherServer() {
        val a = server()
        val b = server()
        a.set(Scope.Global, "x", "1")
        a.settle()
        b.poll()
        assertEquals("1", b[Scope.Global, "x"]?.value)
        a.remove(Scope.Global, "x")
        a.settle()
        b.poll()
        assertNull(b[Scope.Global, "x"], "a removal is heard of as well")
        assertTrue(b.set(Scope.Global, "x", "2", null, false), "and the name is free again")
    }

    @Test
    fun numbersFromTwoServersAddUp() {
        val a = server()
        val b = server()
        val threads = listOf(a, b).map { server ->
            thread { repeat(40) { server.change(Scope.Global, "count") { it + BigDecimal.ONE } } }
        }
        threads.forEach(Thread::join)
        a.settle()
        b.settle()
        a.poll()
        b.poll()
        assertEquals("80", a[Scope.Global, "count"]?.value)
        assertEquals("80", b[Scope.Global, "count"]?.value)
    }

    @Test
    fun theDatabaseDecidesWhoseSetWins() {
        val a = server()
        val b = server()
        a.set(Scope.Global, "claimed", "a")
        a.settle()
        // b has not looked yet, so it believes it is first; the database says otherwise.
        assertTrue(b.set(Scope.Global, "claimed", "b", null, false))
        b.settle()
        assertEquals("a", b[Scope.Global, "claimed"]?.value)
        a.poll()
        assertEquals("a", a[Scope.Global, "claimed"]?.value)
    }

    @Test
    fun aServerThatStartsLaterLoadsEverything() {
        val a = server()
        val player = Scope.Player(java.util.UUID.randomUUID())
        a.set(Scope.Global, "g", "1")
        a.set(player, "p", "2", Instant.now().plusSeconds(600), true)
        a.set(Scope.Global, "gone", "x")
        a.remove(Scope.Global, "gone")
        a.settle()
        val b = server()
        assertEquals("1", b[Scope.Global, "g"]?.value)
        assertEquals("2", b[player, "p"]?.value)
        assertNull(b[Scope.Global, "gone"])
    }

    @Test
    fun nothingIsAvailableUntilItHasConnected() {
        val storage = storage()
        val attempts = AtomicInteger()
        val network = Shard("network", scope, logger, connect = {
            if (attempts.incrementAndGet() < 3) throw SQLTransientConnectionException("down")
            storage.attach()
            SqlBackend(storage)
        })
        val store = VariableStore(rules, Shard("memory", scope, logger), network, network)
        assertFalse(store.isAvailable("x"))
        assertFalse(network.openNow(), "the first try fails")
        assertFalse(store.isAvailable("x"))
        assertFalse(store.set(Scope.Global, "x", "1"), "a change is refused")
        assertNull(store[Scope.Global, "x"])
        assertFalse(store.change(Scope.Global, "x") { it + BigDecimal.ONE })

        network.openInBackground()
        val deadline = System.nanoTime() + 20_000_000_000
        while (!store.isAvailable("x") && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(store.isAvailable("x"), "it connects by itself once the database answers")
        assertTrue(attempts.get() >= 3)
        assertTrue(store.set(Scope.Global, "x", "1"))
        assertEquals("1", store[Scope.Global, "x"]?.value)
    }

    @Test
    fun theServerDoesNotWaitForADatabaseThatIsAway() {
        for (type in listOf("mysql", "mariadb")) {
            val config = MainConfig(
                database = MainConfig.Database(type = type, mysql = MainConfig.Database.Mysql(host = "10.255.255.1")),
                variables = MainConfig.Variables(default = StoreKind.NETWORK),
            )
            val started = System.nanoTime()
            val store = VariableStore.create(directory, config, scope, logger)
            val created = (System.nanoTime() - started) / 1_000_000
            assertTrue(created < 2_000, "$type: creating the stores took ${created}ms")
            assertFalse(store.isAvailable("x"), "$type: the shared store is not ready")
            assertFalse(store.set(Scope.Global, "x", "1"))
            assertNull(store[Scope.Global, "x"])

            val closing = System.nanoTime()
            runBlocking { store.close(kotlin.time.Duration.parse("2s")) }
            val closed = (System.nanoTime() - closing) / 1_000_000
            assertTrue(closed < 6_000, "$type: closing took ${closed}ms")
        }
    }

    @Test
    fun onlyAwayDatabasesAreWorthWaitingFor() {
        assertTrue(RuntimeException("wrapped", SQLTransientConnectionException("down")).isTransient())
        assertFalse(RuntimeException("wrapped", java.sql.SQLSyntaxErrorException("bad")).isTransient())
        assertFalse(IllegalStateException("x").isTransient())
    }

    @Test
    fun theConfigsDefaultsAreWhatTheDocsSay() {
        val defaults = MainConfig()
        assertEquals(StoreKind.LOCAL, defaults.variables.default)
        assertEquals(1000, defaults.variables.pollInterval)
        assertEquals("sqlite", defaults.database.type)
    }
}
