package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigObject
import kotlinx.serialization.json.JsonObject
import mc.xalitoria.menu.paper.XaliMenu
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the editor asks of the plugin, apart from how the question arrived: over the endpoint's HTTP, or through a relay. The routes
 * are listed at [EditorServer].
 *
 * A [Caller] that is [Caller.shared] came through a shared link, which anyone with the link can use. It reads what the editor reads, but
 * a save is not written: it is held in [PendingEdits] for an admin to look at and apply in game, or refused when the link is [Caller.readOnly].
 */
internal class EditorApi(private val plugin: XaliMenu, val menus: EditorMenus, val pending: PendingEdits) {
    /** Told when an edit of a shared link has been put on hold, so that admins can be told. */
    var onHeld: (PendingEdits.Edit) -> Unit = {}

    /** Told when an admin saves a menu through the web editor (`owner`, `name`, `reloaded`, `problems`). */
    var onSaved: (String, String, Boolean, List<String>) -> Unit = { _, _, _, _ -> }

    private val elements = EditorElements(plugin)
    private val packs = ResourcePacks(plugin)
    private val gameFiles = GameFiles(plugin)

    /** Who asks: the owner of the link, and for a shared link the name the person gave. */
    data class Caller(
        val owner: String,
        val shared: Boolean = false,
        val from: String = owner,
        val readOnly: Boolean = false,
        val autoReload: Boolean = false,
        val initialMenu: String? = null,
    )

    /** A question: [path] is the part after `/v1`, with no query. */
    class Call(val method: String, val path: String, val query: String?, val body: ByteArray, val range: String? = null)

    /** The answer. A [file] is sent as it is instead of a [body]. */
    class Reply(
        val status: Int, val type: String, val body: ByteArray = ByteArray(0), val file: Path? = null, val cache: String? = null,
        /** Extra headers, such as `Content-Range` of a part of a file. */
        val headers: Map<String, String> = emptyMap(),
    )

    private inline fun <reified T> json(status: Int, value: T) = Reply(status, JSON, Wire.write(value).toByteArray(), cache = "no-store")

    private fun error(status: Int, message: String) = json(status, ErrorReply(message))

    /** The body of [call] as a [T], or null when it is not one. */
    private inline fun <reified T> body(call: Call): T? = Wire.read<T>(call.body.decodeToString())

    /** The catalogs for `/v1/info`: what their elements hold and, for those of other plugins that said, the keys of their settings. */
    private fun catalogs(): Map<String, CatalogInfo> {
        val settings = plugin.registry.catalogSettings
        return plugin.registry.catalogInfo.mapValues { [name, values] -> CatalogInfo(values, settings[name].orEmpty()) }
    }

    /** Server configuration exposed to the web editor so previews match `config.yml`. */
    private fun serverConfig(): ServerConfigInfo {
        val cfg = plugin.config.data
        val defaultMode = when (cfg.bedrock.defaultMode) {
            mc.xalitoria.menu.paper.config.MainConfig.DefaultBedrockMode.INVENTORY -> "inventory"
            mc.xalitoria.menu.paper.config.MainConfig.DefaultBedrockMode.TOUCH_FORM -> "touch-form"
            mc.xalitoria.menu.paper.config.MainConfig.DefaultBedrockMode.ALWAYS_FORM -> "always-form"
        }
        return ServerConfigInfo(
            clickCooldown = cfg.clickCooldown,
            bedrock = BedrockConfigInfo(
                defaultMode = defaultMode,
                inputForms = cfg.bedrock.inputForms,
                clickFallback = cfg.bedrock.clickFallback,
                pocketInventoryOverlay = cfg.bedrock.pocketInventoryOverlay,
                stripPrivateUseTitle = cfg.bedrock.stripPrivateUseTitle,
                iconUrl = cfg.bedrock.iconUrl,
            ),
        )
    }

    fun handle(call: Call, who: Caller): Reply {
        val path = call.path.trim('/').split('/')
        val method = call.method
        return when {
            path == listOf("info") && method == "GET" -> json(200, Info(
                plugin = "XaliMenu", version = plugin.pluginMeta.version, owner = who.owner, shared = who.shared, readOnly = who.readOnly,
                menus = menus.names(), pack = packs.pack() != null || packs.packUrl() != null, catalogs = catalogs(),
                packSize = packs.pack()?.let { Files.size(it) }, packUrl = packs.packUrl(),
                autoReload = who.autoReload, initialMenu = who.initialMenu, config = serverConfig(),
            ))
            path.size == 2 && path[0] == "menus" && method == "GET" -> {
                val menu = menus.read(path[1]) ?: return error(404, "There is no menu '${path[1]}'")
                json(200, MenuReply(path[1], Wire.tree(menu)))
            }
            path.size == 2 && path[0] == "menus" && method == "PUT" -> {
                val menu = menuOf(call) ?: return error(400, "The request has no 'menu' object")
                if (who.readOnly) return error(403, "This link is read-only")
                if (who.shared) return hold(path[1], menu, who)
                when (val result = menus.save(path[1], menu, reload = who.autoReload)) {
                    is EditorMenus.Result.Refused -> error(422, result.reason)
                    is EditorMenus.Result.Ok -> {
                        log(who.owner, if (result.reloaded) "saves and reloads menu '${path[1]}'" else "saves menu '${path[1]}' (pending reload)")
                        onSaved(who.owner, path[1], result.reloaded, result.problems)
                        json(200, Saved(result.problems, result.reloaded))
                    }
                }
            }
            path.size == 3 && path[0] == "menus" && path[2] == "check" && method == "POST" -> {
                val menu = menuOf(call) ?: return error(400, "The request has no 'menu' object")
                answer(menus.check(path[1], menu))
            }
            path == listOf("pack") && method == "GET" -> {
                val file = packs.pack() ?: return error(404, "There is no resource pack")
                val size = Files.size(file)
                // A part of the pack, for a pack too big to send at once: the editor reads the directory of the zip and then only what it draws.
                val part = call.range?.let { byteRange(it, size) }
                if (call.range != null && part == null) return Reply(416, JSON, Wire.write(ErrorReply("That part is not in the pack")).toByteArray(), headers = mapOf("Content-Range" to "bytes */$size"))
                if (part == null) return Reply(200, "application/zip", file = file)
                val bytes = Files.newByteChannel(file).use { channel ->
                    channel.position(part.first)
                    java.nio.ByteBuffer.allocate((part.last - part.first + 1).toInt()).also { while (it.hasRemaining() && channel.read(it) >= 0) { /* until full */ } }.array()
                }
                Reply(206, "application/zip", bytes, headers = mapOf("Content-Range" to "bytes ${part.first}-${part.last}/$size"))
            }
            path == listOf("colors") && method == "POST" -> {
                val request = body<ColorsRequest>(call) ?: return error(400, "The request is not a list of potions")
                json(200, Colors(packs.potionColors(request.potions.take(200).map { it.type to it.custom() })))
            }
            path == listOf("elements") && method == "POST" -> {
                val request = body<ElementsRequest>(call)
                val catalog = request?.catalog ?: return error(400, "The request has no 'catalog' object")
                when (val result = elements.list(who.owner, Wire.config(catalog), request.sort, request.descending)) {
                    is EditorElements.Result.Ok -> json(200, ElementsReply(result.elements, result.total))
                    is EditorElements.Result.Refused -> error(422, result.reason)
                }
            }
            path == listOf("catalog") && method == "GET" -> json(200, packs.catalog())
            path == listOf("registries") && method == "GET" -> json(200, RegistriesReply(packs.registries()))
            path == listOf("glyphs") && method == "GET" -> json(200, Glyphs(packs.glyphs()))
            path == listOf("skin") && method == "GET" -> {
                val params = call.query.orEmpty().split('&').mapNotNull { entry ->
                    val eq = entry.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                    entry.substring(0, eq) to java.net.URLDecoder.decode(entry.substring(eq + 1), Charsets.UTF_8)
                }.toMap()
                val address = params["url"] ?: params["player"]?.let { name ->
                    if (!PLAYER_NAME.matches(name)) return error(400, "Not a valid player name")
                    plugin.heads.skinUrl(name) ?: return error(404, "No skin found for '$name'")
                }
                val url = address?.let(::skinAddress) ?: return error(400, "Only skins on textures.minecraft.net can be fetched")
                val bytes = fetchSkin(url) ?: return error(404, "The skin could not be fetched")
                Reply(200, "image/png", bytes, cache = "max-age=86400")
            }
            path == listOf("game-file") && method == "GET" -> {
                val name = call.query.orEmpty().split('&').firstOrNull { it.startsWith("name=") }
                    ?.removePrefix("name=")?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                if (name == null || !GameFiles.allowed(name)) return error(400, "Only the zip files of the game's font folder can be fetched")
                val file = gameFiles.file(name) ?: return error(404, "The file could not be fetched from Mojang")
                Reply(200, "application/zip", file = file, cache = "max-age=86400")
            }
            path == listOf("items") && method == "POST" -> {
                val request = body<ItemsRequest>(call) ?: return error(400, "The request is not a list of materials")
                json(200, ItemsReply(packs.items(request.materials.take(500))))
            }
            else -> error(404, "Unknown request")
        }
    }

    /** Checks the menu of a shared link and keeps it for an admin; nothing is written. */
    private fun hold(name: String, menu: ConfigObject, who: Caller): Reply = when (val checked = menus.check(name, menu)) {
        is EditorMenus.Result.Refused -> error(422, checked.reason)
        is EditorMenus.Result.Ok -> {
            val edit = pending.add(who.from, name, menu, checked.problems)
            log(who.from, "sent an edit of menu '$name' (#${edit.id}) through a shared link")
            onHeld(edit)
            json(200, Held(checked.problems, held = true, id = edit.id))
        }
    }

    private fun answer(result: EditorMenus.Result) = when (result) {
        is EditorMenus.Result.Ok -> json(200, Problems(result.problems))
        is EditorMenus.Result.Refused -> error(422, result.reason)
    }

    /** The `menu` object of the request, or null if it is not there. */
    private fun menuOf(call: Call): ConfigObject? = body<MenuRequest>(call)?.menu?.let(Wire::config)

    private fun log(who: String, what: String) = plugin.logger.info("$who $what in the editor")

    /** The skin at [url], or null if it cannot be had or is bigger than a skin can be. */
    private fun fetchSkin(url: URI): ByteArray? = try {
        Http.get(url, java.time.Duration.ofSeconds(8), MAX_SKIN)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The largest menu or request the editor may send. */
        const val MAX_BODY = 2 * 1024 * 1024
        private const val JSON = "application/json; charset=utf-8"
        private const val MAX_SKIN = 256 * 1024

        /** The most of a pack that one request returns; the editor asks again for the rest. */
        const val MAX_PART = 4 * 1024 * 1024

        /**
         * The bytes `bytes=<first>-<last>` asks for in a file of [size], with the end cut to the file and to [MAX_PART], or null when the header
         * is not one of that form or starts outside the file. `bytes=<first>-` goes to the end.
         */
        fun byteRange(header: String, size: Long): LongRange? {
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(header.trim()) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].toLongOrNull() ?: (size - 1)
            if (first >= size || last < first) return null
            return first..minOf(last, size - 1, first + MAX_PART - 1)
        }
        private val SKIN_PATH = Regex("/texture/[0-9a-f]{16,128}")
        val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")

        /**
         * The address to fetch for [text] if it is a skin on Mojang's texture server, as https, otherwise null. The endpoint fetches
         * for the editor, so it may fetch nothing but skins.
         */
        fun skinAddress(text: String): URI? {
            val uri = runCatching { URI(text.replaceFirst("http://", "https://")) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "textures.minecraft.net" || uri.port != -1 || uri.userInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null || !SKIN_PATH.matches(uri.rawPath)) return null
            return uri
        }
    }
}
