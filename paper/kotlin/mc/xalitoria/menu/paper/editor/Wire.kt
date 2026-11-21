package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigRenderOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the editor and the plugin say to each other, as classes: a request is read into one and a reply is written from one, so a misspelled key or a value of the
 * wrong kind is a compile error or a refused request and not a `null` found at run time. The field names are the editor's (`web/src/remote.js` and `relay.js`).
 * A menu itself is a tree the plugin reads with its own model, so it travels as a [JsonElement] and is turned into a config object where it is read.
 */
internal object Wire {
    /** Unknown keys of a request are ignored (a newer editor may send more), and every key of a reply is written, `null` included. */
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    inline fun <reified T> write(value: T): String = json.encodeToString(value)

    /** [text] read as a [T], or null when it is not one. */
    inline fun <reified T> read(text: String): T? = try { json.decodeFromString<T>(text) } catch (e: Exception) { null }

    /** A menu (or a part of one) as the config object the plugin reads it through. */
    fun config(value: JsonObject): ConfigObject = ConfigFactory.parseString(value.toString()).root()

    /** A config object as JSON, for the editor. */
    fun tree(config: ConfigObject): JsonElement = json.parseToJsonElement(config.render(ConfigRenderOptions.concise()))
}

// ---- replies of the endpoint

@Serializable internal data class ErrorReply(val error: String)

@Serializable internal data class Problems(val problems: List<String>)

/** The answer to the save of a shared link, which is kept for an admin and not written. */
@Serializable internal data class Held(val problems: List<String>, val held: Boolean, val id: Int)

@Serializable internal data class CatalogInfo(val values: Map<String, String>, val settings: Map<String, String>)

@Serializable internal data class Info(
    val plugin: String, val version: String, val owner: String, val shared: Boolean, val readOnly: Boolean, val menus: List<String>, val pack: Boolean,
    val catalogs: Map<String, CatalogInfo>, val packSize: Long?, val packUrl: String?,
)

@Serializable internal data class MenuReply(val name: String, val menu: JsonElement)

@Serializable internal data class Colors(val colors: List<Int?>)

@Serializable internal data class ElementsReply(val elements: List<Map<String, String>>, val total: Int)

/** The items of the game, and of each source that lists its own, by prefix. */
@Serializable internal data class ItemCatalog(val vanilla: List<String>, val custom: Map<String, List<String>>)

/** Live server registry keys (sounds, particles, enchantments, effects, entity types, ...). */
@Serializable internal data class RegistriesReply(val registries: Map<String, List<String>>)

@Serializable internal data class Glyph(val name: String, val char: String, val plugin: String)

@Serializable internal data class Glyphs(val glyphs: List<Glyph>)

/** What an item is, for the editor to draw it: the item it is made of, its item model, its custom model data, and how much it wears (0 for nothing). */
@Serializable internal data class ItemInfo(val material: String, val itemModel: String?, val customModelData: List<Double>?, val maxDamage: Int)

@Serializable internal data class ItemsReply(val items: Map<String, ItemInfo>)

// ---- requests of the editor

/** A save or a check of a menu. */
@Serializable internal data class MenuRequest(val menu: JsonObject? = null)

@Serializable internal data class EffectSpec(val type: String? = null, val amplifier: Int = 0)

@Serializable internal data class PotionSpec(val type: String? = null, val effects: List<EffectSpec> = []) {
    /** The effects that name a kind, as the pairs the colors are worked out from. */
    fun custom(): List<Pair<String, Int>> = effects.mapNotNull { effect -> effect.type?.let { it to effect.amplifier } }
}

@Serializable internal data class ColorsRequest(val potions: List<PotionSpec> = [])

@Serializable internal data class ElementsRequest(val catalog: JsonObject? = null, val sort: String? = null, val descending: Boolean = false)

@Serializable internal data class ItemsRequest(val materials: List<String> = [])

// ---- the relay

/** What the relay says when a session is made. */
@Serializable internal data class RelaySession(val id: String, val secret: String)

/** One question the relay holds for the plugin: [data] is sealed, and base64 of a [RelayRequest] once opened. */
@Serializable internal data class RelayCall(val id: String? = null, val data: String? = null)

@Serializable internal data class RelayRequest(
    val rid: String? = null, val t: Long? = null, val method: String? = null, val path: String? = null, val from: String? = null, val body: String? = null, val range: String? = null,
)

/** The answer to a [RelayRequest]; [body] is base64. */
@Serializable internal data class RelayReply(val rid: String, val status: Int, val type: String, val body: String)

// ---- Mojang's files

@Serializable internal data class VersionManifest(val versions: List<VersionEntry> = [])

@Serializable internal data class VersionEntry(val id: String, val url: String)

@Serializable internal data class VersionDescription(val assetIndex: AssetIndexRef? = null)

@Serializable internal data class AssetIndexRef(val url: String)

@Serializable internal data class AssetIndex(val objects: Map<String, AssetObject> = emptyMap())

@Serializable internal data class AssetObject(val hash: String)
