package mc.xalitoria.menu.paper.variable

import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mc.xalitoria.menu.api.Variable

/**
 * The variables of one store. Everything is read from memory. A change is made in memory at once and, if the store has a
 * database, queued to be written there by one background coroutine in order; the database answers with what it ended up
 * holding, which then replaces the optimistic copy. A store that is shared with other servers also asks the database
 * every [pollEvery] for what they changed.
 *
 * It is [ready] when it is loaded. [connect] makes the connection and is what is tried again, with a growing wait, until it
 * works; without one the store is memory only and ready at once. Until it is ready nothing is read and no change is made.
 */
internal class Shard(
    val name: String,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val connect: (() -> SqlBackend)? = null,
    private val pollEvery: Duration? = null,
    private val onReady: (Shard) -> Unit = {},
) {
    private class Entry(val variable: Variable, val rev: Long)

    @Volatile
    var ready = false
        private set

    private val cache = ConcurrentHashMap<Key, Entry>()

    /** Per variable, the changes queued or running; while there are any, what other servers wrote is not taken over it. */
    private val pending = ConcurrentHashMap<Key, Int>()
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var backend: SqlBackend? = null
    private var lastRev = 0L
    private var failures = 0
    private var outage = false
    private var pollFailing = false
    private var writer: Job? = null
    private var background = emptyList<Job>()

    init {
        if (connect == null) {
            ready = true
            background = listOf(sweeper())
        }
    }

    val size: Int get() = cache.size

    /** One try at connecting and loading, which waits for the database; whether it worked. */
    fun openNow(): Boolean {
        val connect = connect ?: return true
        return try {
            val sql = connect()
            val loaded = sql.load()
            for (row in loaded.rows) cache[row.key] = Entry(row.variable(), row.rev)
            lastRev = loaded.rev
            backend = sql
            ready = true
            startBackground(sql)
            onReady(this)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
            if (failures == 1 || failures % 10 == 0) {
                logger.warning("The $name variable store is not reachable (${e.message}); trying again, and until then its variables read as unresolved.")
            }
            false
        }
    }

    /** Tries to open on a background thread, again and again, never on the thread that calls this. [after] is the wait before the first try. */
    fun openInBackground(after: Duration = Duration.ZERO) {
        if (connect == null) return
        background += scope.launch(Dispatchers.IO) {
            var wait = 2.seconds
            delay(after)
            while (isActive && !openNow()) {
                delay(wait)
                wait = minOf(wait * 2, 30.seconds)
            }
        }
    }

    fun get(key: Key): Variable? = cache[key]?.variable?.takeUnless { it.isExpired() }

    fun set(key: Key, value: String, expiry: Instant?, replace: Boolean): Boolean {
        if (!ready) return false
        val next = Variable(key.name, value, expiry)
        if (next.isExpired()) return false
        track(key)
        var written = false
        cache.compute(key) { _, old ->
            if (old != null && !old.variable.isExpired() && !replace) old else Entry(next, old?.rev ?: 0).also { written = true }
        }
        if (written) submit(Write.Put(key, value, expiry?.toEpochMilli(), replace)) else untrack(key)
        return written
    }

    fun remove(key: Key): Boolean {
        if (!ready) return false
        track(key)
        val old = cache.remove(key)
        if (old != null) submit(Write.Remove(key)) else untrack(key)
        return old != null && !old.variable.isExpired()
    }

    fun change(key: Key, change: (BigDecimal) -> BigDecimal): Boolean {
        if (!ready) return false
        track(key)
        var ok = true
        var wrote = false
        cache.compute(key) { _, old ->
            val live = old?.variable?.takeUnless { it.isExpired() }
            val current = if (live == null) BigDecimal.ZERO else live.number
            val changed = if (current == null) null else try {
                change(current)
            } catch (e: ArithmeticException) {
                null
            }
            if (changed == null) {
                ok = false
                return@compute old
            }
            wrote = true
            Entry(Variable(key.name, Variable.format(changed), live?.expiry), old?.rev ?: 0)
        }
        if (wrote) submit(Write.Change(key, change)) else untrack(key)
        return ok
    }

    /** The names of the variables of [owner] that exist now. */
    fun names(owner: String): List<String> =
        cache.entries.filter { it.key.owner == owner && !it.value.variable.isExpired() }.map { it.key.name }.sorted()

    /** Takes over what other servers changed since the last look. */
    fun poll() {
        val sql = backend ?: return
        try {
            for (row in sql.since(lastRev)) {
                lastRev = maxOf(lastRev, row.rev)
                // A change of ours is on its way and will be newer than this.
                if (pending.containsKey(row.key)) continue
                apply(row)
            }
            if (pollFailing) logger.info("The $name variable store answers again.")
            pollFailing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!pollFailing) logger.warning("The $name variable store could not be asked for changes (${e.message}); its variables may be out of date.")
            pollFailing = true
        }
    }

    /** Drops what has expired from memory, and from the database for good. */
    fun sweep() {
        cache.entries.removeIf { it.value.variable.isExpired() }
        val sql = backend ?: return
        try {
            sql.purge(System.currentTimeMillis(), REMOVALS_KEPT.inWholeMilliseconds)
        } catch (e: Exception) {
            logger.fine("Purging the $name variable store failed: ${e.message}")
        }
    }

    /** Writes what is queued, waiting at most [timeout], then stops. Call it before the database goes away. */
    suspend fun close(timeout: Duration) {
        ready = false
        background.forEach(Job::cancel)
        writes.close()
        val writer = writer ?: return
        if (withTimeoutOrNull(timeout) { writer.join() } == null) {
            writer.cancel()
            val lost = pending.values.sum()
            if (lost > 0) logger.warning("$lost changes to the $name variable store could not be saved.")
        }
    }

    /** Waits until every change made so far has been written (or given up on), at most [timeout]. For tests. */
    suspend fun idle(timeout: Duration = 5.seconds) {
        withTimeoutOrNull(timeout) { while (pending.isNotEmpty()) delay(5) }
    }

    private fun startBackground(sql: SqlBackend) {
        writer = scope.launch(Dispatchers.IO) { for (write in writes) perform(sql, write) }
        val jobs = mutableListOf(sweeper())
        pollEvery?.let { every ->
            jobs += scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(every)
                    poll()
                }
            }
        }
        background += jobs
    }

    private fun sweeper(): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(1.minutes)
            sweep()
        }
    }

    private fun submit(write: Write) {
        if (connect != null) writes.trySend(write)
    }

    private fun track(key: Key) {
        if (connect != null) pending.merge(key, 1, Int::plus)
    }

    /** Takes back a [track] that led to no write. */
    private fun untrack(key: Key) {
        if (connect != null) pending.compute(key) { _, count -> if (count == null || count <= 1) null else count - 1 }
    }

    /** Runs [write] on the database, as long as the database is only away, and keeps what it answers. */
    private suspend fun perform(sql: SqlBackend, write: Write) {
        var wait = 1.seconds
        while (true) {
            try {
                val row = sql.execute(write)
                if (outage) logger.info("The $name variable store can be written to again.")
                outage = false
                finish(write.key) { if (row == null) cache.remove(write.key) else apply(row) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isTransient()) {
                    logger.warning("A change to the $name variable store was dropped: ${e.message}")
                    finish(write.key) {
                        // What memory holds for it was never saved; the database says what is true.
                        try {
                            sql.read(write.key)?.let(::apply) ?: cache.remove(write.key)
                        } catch (again: Exception) {
                            logger.fine("Could not read ${write.key} back: ${again.message}")
                        }
                    }
                    return
                }
                if (!outage) logger.warning("The $name variable store can't be written to (${e.message}); changes wait and are tried again.")
                outage = true
                delay(wait)
                wait = minOf(wait * 2, 30.seconds)
            }
        }
    }

    /** Ends one pending change of [key]; the last one of a key puts what the database answered in memory. */
    private inline fun finish(key: Key, keep: () -> Unit) {
        val remaining = pending.compute(key) { _, count -> if (count == null || count <= 1) null else count - 1 }
        if (remaining == null) keep()
    }

    private fun apply(row: Row) {
        val now = System.currentTimeMillis()
        cache.compute(row.key) { _, old ->
            if (old != null && old.rev > row.rev) old else if (row.isLive(now)) Entry(row.variable(), row.rev) else null
        }
    }

    companion object {
        /** How long a removal stays in the database for the other servers to hear of. */
        val REMOVALS_KEPT = 10.minutes
    }
}
