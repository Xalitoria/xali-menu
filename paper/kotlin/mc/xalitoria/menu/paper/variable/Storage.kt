package mc.xalitoria.menu.paper.variable

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import mc.xalitoria.menu.paper.config.MainConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/**
 * The variables as rows. [scope] is `#global` or a player's id; [expiry] is epoch milliseconds, null for never. A removed
 * variable stays as a row with [deleted] set for a while, so the other servers hear of it; every change gets the next
 * [rev], which is what they ask for ("everything after the last one I saw").
 */
class VariableTable(name: String) : Table(name) {
    val scope = varchar("scope", 40)
    val key = varchar("name", 128)
    val value = text("value")
    val expiry = long("expiry").nullable()
    val deleted = bool("deleted").default(false)
    val rev = long("rev").default(0)
    val updated = long("updated").default(0)

    override val primaryKey = PrimaryKey(scope, key)
}

/**
 * The one row that counts the revisions. A writer bumps it first and keeps the row locked until it commits, so writers
 * take turns and the revisions become visible in order: nobody polling can miss a lower one that commits later.
 */
class RevisionTable(name: String) : Table(name) {
    val id = integer("id")
    val rev = long("rev")

    override val primaryKey = PrimaryKey(id)
}

/**
 * A HikariCP pool and the Exposed [Database] on it. Making one never waits for the database: the pool starts empty, and
 * [attach] (which does) is called when it is time to find out whether it is there.
 */
class Storage private constructor(val source: HikariDataSource, prefix: String) : AutoCloseable {
    private val variableTable = prefix + "variable"
    val variables = VariableTable(variableTable)
    val revision = RevisionTable(prefix + "revision")

    lateinit var database: Database
        private set

    /** Connects and brings the tables up to date. Blocks until the database answers, or fails; may be called again after a failure. */
    @Synchronized
    fun attach() {
        if (!::database.isInitialized) database = Database.connect(source)
        transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(variables, revision, withLogs = false).forEach { exec(it) }
            revision.insertIgnore {
                it[id] = ROW
                it[rev] = 0
            }
        }
        // By hand: the migration doesn't see this index again on SQLite and would try to make it on every start, and
        // MySQL has no "if not exists" for it.
        try {
            transaction(database) { exec("CREATE INDEX ${variableTable}_rev ON $variableTable (rev)") }
        } catch (e: Exception) {
            val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
            if ("exist" !in message && "duplicate" !in message) throw e
        }
    }

    override fun close() = source.close()

    companion object {
        const val ROW = 1
        private val PREFIX = Regex("[A-Za-z0-9_]*")

        private fun checkPrefix(prefix: String) =
            require(PREFIX.matches(prefix)) { "database.table-prefix may only contain letters, digits and underscores" }

        /** The SQLite file [config] names, under [folder]. */
        fun sqlite(folder: Path, config: MainConfig.Database): Storage {
            checkPrefix(config.tablePrefix)
            folder.createDirectories()
            val hikari = HikariConfig()
            hikari.jdbcUrl = "jdbc:sqlite:${folder.resolve(config.sqlite.filename).toAbsolutePath()}"
            hikari.driverClassName = "org.sqlite.JDBC"
            // SQLite takes one writer at a time.
            hikari.maximumPoolSize = 1
            hikari.poolName = "XaliMenu-SQLite"
            return Storage(HikariDataSource(hikari), config.tablePrefix)
        }

        /**
         * The MySQL or MariaDB database [config] names, through the driver of that name: Exposed picks its SQL by the
         * driver, and MariaDB asked questions of MySQL's dialect does not know the answers to. The pool is made without a
         * connection: a host that is down is found out by [attach].
         */
        fun mysql(config: MainConfig.Database): Storage {
            checkPrefix(config.tablePrefix)
            val mysql = config.mysql
            val hikari = HikariConfig()
            if (config.type.equals("mariadb", ignoreCase = true)) {
                hikari.jdbcUrl = "jdbc:mariadb://${mysql.host}:${mysql.port}/${mysql.database}" + if (mysql.useSsl) "?sslMode=trust" else ""
                hikari.driverClassName = "org.mariadb.jdbc.Driver"
            } else {
                hikari.jdbcUrl = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}?useSSL=${mysql.useSsl}"
                hikari.driverClassName = "com.mysql.cj.jdbc.Driver"
            }
            hikari.username = mysql.username
            hikari.password = mysql.password
            hikari.maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
            hikari.minimumIdle = hikari.maximumPoolSize.coerceAtMost(2)
            // Not failing when the pool is made is what keeps the server from waiting on a database that is down.
            hikari.initializationFailTimeout = -1
            hikari.connectionTimeout = 5_000
            hikari.keepaliveTime = 60_000
            hikari.poolName = "XaliMenu-Database"
            return Storage(HikariDataSource(hikari), config.tablePrefix)
        }

        /** Variables on a pool of the caller's making. */
        fun of(source: HikariDataSource, prefix: String): Storage {
            checkPrefix(prefix)
            return Storage(source, prefix)
        }
    }
}
