package mc.xalitoria.menu.paper.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MainConfig(
    @YamlComment("Logs what the plugin is doing, for finding out why a menu doesn't behave.")
    val debug: Boolean = false,
    @YamlComment("The messages file used: language/<language>.yml, created with the defaults if it doesn't exist.")
    val language: String = "en-us",
    @YamlComment("Clicks on a menu closer together than this many milliseconds are ignored, for each player.")
    @SerialName("click-cooldown")
    val clickCooldown: Long = 150,
    @YamlComment(
        "The database. Variables of the local store are always kept in the SQLite file; with type mysql or mariadb the network",
        "store is that shared database too. A new table prefix means new, empty tables. Needs a restart.",
    )
    val database: Database = Database(),
    @YamlComment("Where each variable lives, and how the servers keep their copies of the shared ones current.")
    val variables: Variables = Variables(),
) {
    /** Where a variable is kept. */
    @Serializable
    enum class StoreKind {
        @SerialName("memory") MEMORY,
        @SerialName("local") LOCAL,
        @SerialName("network") NETWORK,
    }

    @Serializable
    data class Variables(
        @YamlComment(
            "The store of a variable no rule matches: memory (gone at restart), local (the SQLite file of this server) or",
            "network (the database all servers share; needs database.type mysql or mariadb, and reads as unresolved until connected).",
        )
        val default: StoreKind = StoreKind.LOCAL,
        @YamlComment(
            "Rules by name, the first match wins; * is any text, ? any one character. For example:",
            "  - { match: 'net_*', store: network }",
            "Changing them needs a restart, and moves nothing: a variable starts empty in its new store.",
        )
        val rules: List<Rule> = [],
        @YamlComment("How often, in milliseconds, this server looks in the shared database for changes made by other servers.")
        @SerialName("poll-interval")
        val pollInterval: Long = 1000,
    ) {
        @Serializable
        data class Rule(val match: String, val store: StoreKind)
    }

    @Serializable
    data class Database(
        @YamlComment("sqlite, mysql or mariadb. Use mariadb for MariaDB: it has its own driver.")
        val type: String = "sqlite",
        @YamlComment("Letters, digits and underscores. Needs a restart.")
        @SerialName("table-prefix")
        val tablePrefix: String = "xalimenu_",
        val sqlite: Sqlite = Sqlite(),
        val mysql: Mysql = Mysql(),
    ) {
        @Serializable
        data class Sqlite(
            val filename: String = "xalimenu.db",
        )

        @Serializable
        data class Mysql(
            val host: String = "localhost",
            val port: Int = 3306,
            val database: String = "xalimenu",
            val username: String = "root",
            val password: String = "",
            @SerialName("use-ssl")
            val useSsl: Boolean = false,
            @SerialName("pool-size")
            val poolSize: Int = 10,
        )
    }
}
