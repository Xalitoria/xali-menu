package mc.xalitoria.menu.paper.variable

import java.math.BigDecimal
import java.util.function.UnaryOperator
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import mc.xalitoria.menu.api.Scope
import mc.xalitoria.menu.api.Variable
import mc.xalitoria.menu.api.Variables
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.config.MainConfig.StoreKind

/** Which store a variable lives in, by its name: the first rule that matches, or the default. */
class StoreRules(private val default: StoreKind, rules: List<MainConfig.Variables.Rule>) {
    private class Compiled(val pattern: Regex, val store: StoreKind)

    private val rules = rules.map { rule ->
        val glob = rule.match.lowercase().map { c ->
            when (c) {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(c.toString())
            }
        }.joinToString("")
        Compiled(Regex(glob), rule.store)
    }

    fun of(name: String): StoreKind {
        val lower = name.lowercase()
        return rules.firstOrNull { it.pattern.matches(lower) }?.store ?: default
    }
}

/**
 * The [Variables] of the plugin: a [Shard] per store, and the rules that say which a name belongs to. The memory store and
 * the server's own SQLite file are ready at once (the file is read before the server goes on); the shared database is
 * connected to in the background and its variables read as unavailable until that has worked.
 */
class VariableStore internal constructor(
    private val rules: StoreRules,
    memory: Shard,
    local: Shard,
    network: Shard?,
    private val storages: List<Storage> = emptyList(),
) : Variables {
    private val shards = mapOf(StoreKind.MEMORY to memory, StoreKind.LOCAL to local, StoreKind.NETWORK to (network ?: local))

    /** The stores that exist, once each. */
    private val distinct = listOfNotNull(memory, local, network)

    private fun shard(name: String): Shard = shards.getValue(rules.of(name))

    private fun key(scope: Scope, name: String) = Key(
        when (scope) {
            Scope.Global -> GLOBAL
            is Scope.Player -> scope.id.toString()
        },
        name.lowercase(),
    )

    override fun get(scope: Scope, name: String): Variable? = shard(name).get(key(scope, name))

    override fun set(scope: Scope, name: String, value: String, expiry: Instant?, replace: Boolean): Boolean =
        shard(name).set(key(scope, name), value, expiry, replace)

    override fun set(scope: Scope, name: String, value: String): Boolean = set(scope, name, value, null, true)

    override fun remove(scope: Scope, name: String): Boolean = shard(name).remove(key(scope, name))

    override fun change(scope: Scope, name: String, change: UnaryOperator<BigDecimal>): Boolean =
        shard(name).change(key(scope, name), change::apply)

    override fun isAvailable(name: String): Boolean = shard(name).ready

    /** The names of every variable of [scope] that exists now, for tab completion. */
    fun names(scope: Scope): List<String> {
        val owner = key(scope, "").owner
        return distinct.flatMap { it.names(owner) }.distinct().sorted()
    }

    /** Writes what is queued (waiting at most [timeout] for each store), then closes the databases. Call it as the plugin goes. */
    suspend fun close(timeout: Duration = 5.seconds) {
        distinct.forEach { it.close(timeout) }
        storages.forEach(Storage::close)
    }

    /** Runs the look for changes of the shared store once, now. For tests. */
    internal fun poll() = distinct.forEach(Shard::poll)

    internal suspend fun idle() = distinct.forEach { it.idle() }

    companion object {
        const val GLOBAL = "#global"

        /**
         * The stores as [config] describes them. The SQLite file is opened before this returns; the shared database, if
         * `database.type` is mysql, is only started on, since it may take its time or not answer at all.
         */
        fun create(folder: Path, config: MainConfig, scope: CoroutineScope, logger: Logger): VariableStore {
            val database = config.database
            val settings = config.variables
            val ready = { shard: Shard -> logger.info("Variables: the ${shard.name} store is ready with ${shard.size} variables.") }
            val storages = mutableListOf<Storage>()

            val memory = Shard("memory", scope, logger)
            // Made at the first try, so that a file that can't be opened is tried again rather than ending the plugin.
            var sqlite: Storage? = null
            val local = Shard("local", scope, logger, connect = {
                val storage = sqlite ?: Storage.sqlite(folder, database).also { sqlite = it; storages += it }
                storage.attach()
                SqlBackend(storage)
            }, onReady = ready)
            if (!local.openNow()) local.openInBackground(after = 2.seconds)

            val network = if (database.type.lowercase() in setOf("mysql", "mariadb")) {
                val storage = Storage.mysql(database).also { storages += it }
                Shard(
                    "network", scope, logger,
                    connect = { storage.attach(); SqlBackend(storage) },
                    pollEvery = settings.pollInterval.coerceAtLeast(100).milliseconds,
                    onReady = ready,
                ).also { it.openInBackground() }
            } else {
                if (settings.default == StoreKind.NETWORK || settings.rules.any { it.store == StoreKind.NETWORK }) {
                    logger.warning("Variables are set to use the network store, but database.type is not mysql; they are kept in the local store instead.")
                }
                null
            }
            return VariableStore(StoreRules(settings.default, settings.rules), memory, local, network, storages)
        }
    }
}
