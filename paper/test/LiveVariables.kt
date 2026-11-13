import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mc.xalitoria.menu.paper.config.MainConfig.StoreKind
import mc.xalitoria.menu.paper.runtime.showsVariables
import mc.xalitoria.menu.paper.util.batched
import mc.xalitoria.menu.paper.variable.Key
import mc.xalitoria.menu.paper.variable.Shard
import mc.xalitoria.menu.paper.variable.StoreRules
import mc.xalitoria.menu.paper.variable.VariableStore
import net.xalitoria.menu.Scope
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** What shows a variable hears when it changes: the stream of changes, the grouping of a burst, and which menus care. */
class LiveVariables {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = Scope.Player(UUID.fromString("00000000-0000-0000-0000-000000000042"))

    private fun store(): VariableStore {
        val logger = Logger.getLogger("test")
        return VariableStore(StoreRules(StoreKind.LOCAL, emptyList()), Shard("memory", scope, logger), Shard("local", scope, logger), null)
    }

    /** What [store] announces while [block] runs, in order. */
    private fun heard(store: VariableStore, block: () -> Unit): List<Key> {
        val keys = mutableListOf<Key>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { store.changes.collect { synchronized(keys) { keys += it } } }
        block()
        Thread.sleep(100)
        job.cancel()
        return synchronized(keys) { keys.toList() }
    }

    @Test
    fun aChangeIsAnnouncedWithWhoseAndWhich() {
        val store = store()
        val keys = heard(store) {
            store.set(Scope.Global, "Coins", "5")
            store.set(player, "coins", "9")
            store.change(Scope.Global, "coins") { it + java.math.BigDecimal.ONE }
            store.remove(Scope.Global, "coins")
        }
        assertEquals(listOf(Key("#global", "coins"), Key("00000000-0000-0000-0000-000000000042", "coins"), Key("#global", "coins"), Key("#global", "coins")), keys)
    }

    @Test
    fun whatChangedNothingIsNotAnnounced() {
        val store = store()
        store.set(Scope.Global, "a", "1")
        val keys = heard(store) {
            store.set(Scope.Global, "a", "2", null, false) // kept as it is
            store.remove(Scope.Global, "nothing")
            store.change(Scope.Global, "a") { throw ArithmeticException() }
        }
        assertEquals(emptyList(), keys)
    }

    @Test
    fun aBurstIsOneBatchWithEachKeyOnce() = runBlocking {
        val source = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val batches = mutableListOf<Set<String>>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { source.batched(60.milliseconds).collect { batches += it } }
        for (key in listOf("a", "b", "a", "c", "b")) source.emit(key)
        delay(200)
        source.emit("d")
        delay(200)
        job.cancel()
        assertEquals(listOf(setOf("a", "b", "c"), setOf("d")), batches, "nothing is dropped and nothing repeats")
    }

    @Test
    fun theLastBatchIsSentWhenTheFlowEnds() = runBlocking {
        val all = withTimeout(2000) { kotlinx.coroutines.flow.flowOf(1, 2, 3).batched(50.milliseconds).toList() }
        assertEquals(listOf(setOf(1, 2, 3)), all)
    }

    @Test
    fun aMenuShowsVariablesWhenItNamesAPlaceholderOfThem() {
        assertTrue(showsVariables("""name = "Coins: %xalimenu_var_coins:0%""""))
        assertTrue(showsVariables("%xalimenu_varp_kills%"))
        assertTrue(showsVariables("%XaliMenu_vart_boost%"))
        assertFalse(showsVariables("%player_name% and %xalimenu_page%"))
        assertFalse(showsVariables("xalimenu_var without the percent"))
    }

    @AfterTest
    fun stop() = scope.cancel()
}
