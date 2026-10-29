package mc.xalitoria.menu.paper.variable

import java.math.BigDecimal
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.time.Instant
import mc.xalitoria.menu.api.Variable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

/** Whose variable and which: [owner] is `#global` or a player's id. */
internal data class Key(val owner: String, val name: String)

/** A variable as the database has it; [rev] says how recent that is. A [deleted] one is only there to be heard of. */
internal data class Row(val key: Key, val value: String, val expiry: Long?, val deleted: Boolean, val rev: Long) {
    fun isLive(now: Long): Boolean = !deleted && (expiry == null || expiry > now)

    fun variable() = Variable(key.name, value, expiry?.let(Instant::ofEpochMilli))
}

/** A change to make in the database, which decides what it comes to. */
internal sealed interface Write {
    val key: Key

    class Put(override val key: Key, val value: String, val expiry: Long?, val replace: Boolean) : Write

    class Remove(override val key: Key) : Write

    class Change(override val key: Key, val change: (BigDecimal) -> BigDecimal) : Write
}

/** Every variable that exists, and the revision they are as of: ask for what came after it to stay current. */
internal class Loaded(val rows: List<Row>, val rev: Long)

/**
 * The variables in a database. Every change is a transaction that first takes the turn (see [RevisionTable]), so changes
 * from different servers are applied one after the other, each on what the one before left, and a number that two
 * servers add to adds up. What each call comes back with is what the database ended up holding, for the caller to keep.
 */
internal class SqlBackend(private val storage: Storage) {
    private val table get() = storage.variables
    private val revision get() = storage.revision

    fun load(): Loaded = transaction(storage.database) {
        // The counter is read first: what is read after it can only be as of it or newer, and newer is fetched again.
        val rev = revision.selectAll().where { revision.id eq Storage.ROW }.single()[revision.rev]
        val now = System.currentTimeMillis()
        Loaded(table.selectAll().map { it.toRow() }.filter { it.isLive(now) }, rev)
    }

    /** What changed after revision [rev], removals included. */
    fun since(rev: Long): List<Row> = transaction(storage.database) {
        table.selectAll().where { table.rev greater rev }.orderBy(table.rev).map { it.toRow() }
    }

    fun read(key: Key): Row? = transaction(storage.database) { find(key, lock = false) }

    fun execute(write: Write): Row? = transaction(storage.database) {
        val rev = nextRevision()
        val now = System.currentTimeMillis()
        val key = write.key
        val current = find(key, lock = true)
        val live = current?.takeIf { it.isLive(now) }
        when (write) {
            is Write.Put ->
                if (live != null && !write.replace) live
                else store(Row(key, write.value, write.expiry, false, rev), now)
            is Write.Remove ->
                if (live == null) current else store(Row(key, "", null, true, rev), now)
            is Write.Change -> {
                val base = if (live == null) BigDecimal.ZERO else live.variable().number
                if (base == null) live else store(Row(key, Variable.format(write.change(base)), live?.expiry, false, rev), now)
            }
        }
    }

    /** Takes the turn, and with it the next revision. */
    private fun JdbcTransaction.nextRevision(): Long {
        revision.update({ revision.id eq Storage.ROW }) { it[rev] = rev + 1L }
        return revision.selectAll().where { revision.id eq Storage.ROW }.single()[revision.rev]
    }

    private fun find(key: Key, lock: Boolean): Row? =
        table.selectAll().where { (table.scope eq key.owner) and (table.key eq key.name) }
            .let { if (lock) it.forUpdate() else it }
            .singleOrNull()?.toRow()

    private fun store(row: Row, now: Long): Row {
        table.upsert {
            it[scope] = row.key.owner
            it[key] = row.key.name
            it[value] = row.value
            it[expiry] = row.expiry
            it[deleted] = row.deleted
            it[rev] = row.rev
            it[updated] = now
        }
        return row
    }

    /** Drops variables that have expired, and removals the other servers have had time to hear of. */
    fun purge(now: Long, removalsKeptMs: Long) {
        transaction(storage.database) {
            table.deleteWhere { (table.deleted eq true) and (table.updated less now - removalsKeptMs) }
            table.deleteWhere { (table.deleted eq false) and table.expiry.isNotNull() and (table.expiry less now) }
        }
    }

    private fun ResultRow.toRow() =
        Row(Key(this[table.scope], this[table.key]), this[table.value], this[table.expiry], this[table.deleted], this[table.rev])
}

/** Whether trying again later could work: the database is away or busy, rather than the statement being wrong. */
internal fun Throwable.isTransient(): Boolean = generateSequence(this) { it.cause }.any {
    it is SQLTransientException || it is SQLRecoverableException || it is SQLNonTransientConnectionException || it is java.net.SocketException
}
