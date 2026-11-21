package mc.xalitoria.menu.paper.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.config.DatabaseConfig
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
    val database: DatabaseConfig = DatabaseConfig(
        tablePrefix = "xalimenu_",
        sqlite = DatabaseConfig.Sqlite("xalimenu.db"),
        mysql = DatabaseConfig.Mysql(database = "xalimenu"),
    ),
    @YamlComment("Where each variable lives, and how the servers keep their copies of the shared ones current.")
    val variables: Variables = Variables(),
    @YamlComment(
        "The web editor for menus, at the address of `site`. By default `/xmenu edit` opens an end-to-end encrypted session",
        "through `relay.url` (`https://menu.xali.to/relay`) and prints a short 12-character link (`https://menu.xali.to/#<code>`).",
        "Saved menus write to disk and apply on `/xmenu reload` unless `--auto-reload` is passed. Turn `enabled` on instead",
        "only if you want the plugin to listen directly on `host` and `port` without a relay.",
    )
    val editor: Editor = Editor(),
    @YamlComment(
        "Bedrock Edition support through Floodgate or Geyser-Spigot: default form conversion, native text-input forms,",
        "touch/controller click adaptation and Pocket UI safety.",
    )
    val bedrock: Bedrock = Bedrock(),
) {
    /** Default presentation for Bedrock players when a menu's `bedrock.mode` is `AUTO` or absent. */
    @Serializable
    enum class DefaultBedrockMode {
        @SerialName("inventory") INVENTORY,
        @SerialName("touch-form") TOUCH_FORM,
        @SerialName("always-form") ALWAYS_FORM,
    }

    @Serializable
    data class Bedrock(
        @YamlComment(
            "How menus without explicit Bedrock form settings open for Bedrock players: `inventory` (chest window),",
            "`touch-form` (SimpleForm for touch/controller/Pocket UI, chest window for Bedrock PC Classic UI), or",
            "`always-form` (SimpleForm for every Bedrock player).",
        )
        @SerialName("default-mode")
        val defaultMode: DefaultBedrockMode = DefaultBedrockMode.INVENTORY,
        @YamlComment("Use a native Bedrock CustomForm for `input` actions when `mode` is `AUTO` or `FORM`.")
        @SerialName("input-forms")
        val inputForms: Boolean = true,
        @YamlComment(
            "When a Bedrock player activates an inventory item with no handler for that click kind, fall back to its",
            "only click handler or open a choice form when several click handlers exist (such as left and right click).",
        )
        @SerialName("click-fallback")
        val clickFallback: Boolean = true,
        @YamlComment("Send `inventory-slots` packet overlays to Bedrock Pocket UI players. Off by default because Pocket UI handles them poorly.")
        @SerialName("pocket-inventory-overlay")
        val pocketInventoryOverlay: Boolean = false,
        @YamlComment("Remove Private Use Area glyphs (Java resource-pack shifts/textures) from Bedrock inventory titles when `bedrock.title` is not set.")
        @SerialName("strip-private-use-title")
        val stripPrivateUseTitle: Boolean = true,
        @YamlComment(
            "Optional URL template for vanilla item icons on Bedrock forms, with `{material}` replaced by the lowercase",
            "item key (for example `https://example.com/icons/{material}.png`). Empty uses Bedrock's built-in texture paths.",
        )
        @SerialName("icon-url")
        val iconUrl: String = "",
    )
    @Serializable
    data class Editor(
        val enabled: Boolean = false,
        @YamlComment("The address the plugin listens on. 127.0.0.1 only accepts the machine itself; use a reverse proxy for anything else.")
        val host: String = "127.0.0.1",
        val port: Int = 8123,
        @YamlComment("The address the editor uses to reach this server, as the admin's browser sees it. Empty: http://host:port.")
        @SerialName("public-url")
        val publicUrl: String = "",
        @YamlComment("Where the editor is hosted. Only this site (and localhost, for development) may call the plugin.")
        val site: String = "https://menu.xali.to",
        @YamlComment("How long the link from /xmenu edit works, in minutes.")
        @SerialName("session-minutes")
        val sessionMinutes: Int = 60,
        @YamlComment(
            "The resource pack the editor shows, as a path of a zip file. Empty: the pack of Nexo, Oraxen or ItemsAdder, whichever",
            "is installed. The pack is sent to the editor, which never stores it.",
        )
        val pack: String = "",
        @YamlComment(
            "Where the resource pack is hosted for the players, such as an S3 or CDN address. The editor downloads from there when it can, which",
            "is much better for a big pack. Empty: what Nexo says it sends to players, if Nexo is installed; otherwise the pack goes through this server.",
        )
        @SerialName("pack-url")
        val packUrl: String = "",
        @YamlComment(
            "The relay lets `/xmenu edit` and `/xmenu share` connect to the web editor with outbound connections only. Traffic",
            "is encrypted end-to-end with a key derived from the 12-character link fragment, which the relay never sees.",
            "Set `url` to empty to disable the relay.",
        )
        val relay: Relay = Relay(),
    ) {
        @Serializable
        data class Relay(
            @YamlComment("The address of the relay. Default `https://menu.xali.to/relay` produces short 12-character links.")
            val url: String = "https://menu.xali.to/relay",
            @YamlComment("The token the relay asks for when it opens sessions, if it does.")
            val token: String = "",
        )
    }

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
}
