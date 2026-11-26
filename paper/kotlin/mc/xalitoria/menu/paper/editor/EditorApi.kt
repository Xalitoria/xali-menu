package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigObject
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.command.Permissions
import org.bukkit.entity.Player
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * What the editor asks of the plugin, apart from how the question arrived: over the endpoint's HTTP, or through a relay. The routes
 * are listed at [EditorServer].
 *
 * A [Caller] that is [Caller.shared] came through a shared link, which anyone with the link can use. It reads what the editor reads, but
 * a save is not written: it is held in [PendingEdits] for an admin to look at and apply in game, or refused when the link is [Caller.readOnly].
 * Admin edit links also verify the caller's IP against [EditorGuard] upfront (`/v1/info`, `/v1/presence`) and again on save (`PUT /v1/menus/<name>`).
 */
internal class EditorApi(private val plugin: XaliMenu, val menus: EditorMenus, val pending: PendingEdits) {
    /** Told when an edit has been put on hold (shared link or IP mismatch), so that admins can be told. */
    var onHeld: (PendingEdits.Edit) -> Unit = {}

    /** Told when an admin saves a menu through the web editor (`owner`, `name`, `reloaded`, `problems`). */
    var onSaved: (String, String, Boolean, List<String>) -> Unit = { _, _, _, _ -> }

    private val elements = EditorElements(plugin)
    private val packs = ResourcePacks(plugin)
    private val gameFiles = GameFiles(plugin)
    val presence = EditorPresence()
    private val packRevision = AtomicLong(1)

    /** Bumps the resource-pack revision and drops cached hashes so active editor tabs automatically refresh their pack. */
    fun notifyPackUpdated(source: String) {
        packs.invalidateCache()
        val rev = packRevision.incrementAndGet()
        if (presence.activeCount() > 0) {
            plugin.logger.info("$source reloaded its resource pack; notified active web-editor tabs (revision $rev).")
        }
    }

    /** Who asks: the owner of the link, optional creator IP/UUID, the browser's client IP, and for a shared link the name given. */
    data class Caller(
        val owner: String,
        val shared: Boolean = false,
        val from: String = owner,
        val readOnly: Boolean = false,
        val autoReload: Boolean = false,
        val initialMenu: String? = null,
        val ownerIp: String? = null,
        val ownerUuid: UUID? = null,
        val clientIp: String? = null,
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

    /** Online player uniquely matching [clientIp]; returns null when multiple players share the IP (NAT/LAN/proxy). */
    private fun onlinePlayerFor(clientIp: String?): Player? {
        if (clientIp == null) return null
        val matches = plugin.server.onlinePlayers.filter { player ->
            val ip = runCatching { player.address?.address?.hostAddress }.getOrNull()
            EditorGuard.ipsMatch(ip, clientIp)
        }
        return matches.singleOrNull()
    }

    /** Normalized IPs of currently online players, split into admins and non-admins. */
    private fun onlineIpsByRole(): Pair<List<String>, List<String>> {
        val admins = ArrayList<String>()
        val nonAdmins = ArrayList<String>()
        for (player in plugin.server.onlinePlayers) {
            val ip = runCatching { EditorGuard.normalizeIp(player.address?.address?.hostAddress) }.getOrNull() ?: continue
            val isAdmin = runCatching { player.hasPermission(Permissions.ADMIN) }.getOrDefault(false)
            if (isAdmin) admins.add(ip) else nonAdmins.add(ip)
        }
        return admins to nonAdmins
    }

    /** Evaluates the current save/IP authorization state for [who] (used upfront in `/info` & `/presence` and enforced on `PUT`). */
    private fun evaluateAccess(who: Caller): Pair<EditorGuard.SaveDecision, Player?> {
        val matched = onlinePlayerFor(who.clientIp)
        val ownerStillAllowed = who.ownerUuid?.let { uuid ->
            val player = plugin.server.getPlayer(uuid)
            player != null && runCatching { player.hasPermission(Permissions.ADMIN) }.getOrDefault(false)
        } ?: true
        val [adminIps, nonAdminIps] = onlineIpsByRole()
        val ipSharedWithOtherPlayer = who.clientIp != null && plugin.server.onlinePlayers.any { player ->
            player.uniqueId != who.ownerUuid &&
                EditorGuard.ipsMatch(runCatching { player.address?.address?.hostAddress }.getOrNull(), who.clientIp)
        }
        val decision = EditorGuard.checkSave(
            policy = plugin.config.data.editor.verifyIp,
            shared = who.shared,
            readOnly = who.readOnly,
            ownerIp = who.ownerIp,
            ownerStillAllowed = ownerStillAllowed,
            clientIp = who.clientIp,
            onlineAdminIps = adminIps,
            onlineNonAdminIps = nonAdminIps,
            ipSharedWithOtherPlayer = ipSharedWithOtherPlayer,
        )
        return decision to matched
    }

    private fun accessStatus(who: Caller, decision: EditorGuard.SaveDecision): Pair<Boolean, String?> = when (decision) {
        EditorGuard.SaveDecision.Allow -> true to null
        EditorGuard.SaveDecision.ReadOnly -> false to null
        EditorGuard.SaveDecision.PermissionRevoked -> false to "${who.owner} must be online with ${Permissions.ADMIN} permission to save menus."
        is EditorGuard.SaveDecision.Hold -> if (who.shared) {
            true to null
        } else {
            false to "IP check (${decision.reason}): your browser IP does not uniquely match an online admin in-game. Changes can still be edited and submitted, but saves will be held in /xmenu edits until approved in-game."
        }
    }

    fun handle(call: Call, who: Caller): Reply {
        val path = call.path.trim('/').split('/')
        val method = call.method
        return when {
            path == listOf("info") && method == "GET" -> {
                val [decision, matched] = evaluateAccess(who)
                val [ipVerified, ipNotice] = accessStatus(who, decision)
                val packFile = packs.pack()
                json(200, Info(
                    plugin = "XaliMenu", version = plugin.pluginMeta.version, owner = who.owner, shared = who.shared, readOnly = who.readOnly,
                    menus = menus.names(), pack = packFile != null || packs.packUrl() != null, catalogs = catalogs(),
                    packSize = packFile?.let { Files.size(it) }, packUrl = packs.packUrl(),
                    autoReload = who.autoReload, initialMenu = who.initialMenu, config = serverConfig(), packHash = packs.packHash(),
                    serverVersion = plugin.server.minecraftVersion, packRevision = packRevision.get(),
                    ipVerified = ipVerified, ipNotice = ipNotice, clientName = matched?.name,
                ))
            }
            path == listOf("presence") && method == "POST" -> {
                val request = body<PresenceRequest>(call) ?: return error(400, "Invalid presence heartbeat")
                val clientId = request.clientId ?: return error(400, "Missing clientId")
                val [decision, matched] = evaluateAccess(who)
                val [ipVerified, ipNotice] = accessStatus(who, decision)
                val inGame = matched != null
                val isAdmin = matched?.let { runCatching { it.hasPermission(Permissions.ADMIN) }.getOrDefault(false) } ?: false
                val displayName = matched?.name ?: request.name?.takeIf { it.isNotBlank() } ?: who.from.takeIf { who.shared && it != who.owner }
                val peers = presence.update(EditorPresence.Beat(
                    clientId = clientId,
                    name = displayName,
                    inGame = inGame,
                    admin = isAdmin,
                    menu = request.menu,
                    item = request.item,
                    slot = request.slot,
                    area = request.area,
                    tab = request.tab,
                ))
                val packFile = packs.pack()
                json(200, PresenceReply(
                    peers = peers,
                    packRevision = packRevision.get(),
                    packHash = packs.packHash(),
                    packSize = packFile?.let { Files.size(it) },
                    packUrl = packs.packUrl(),
                    ipVerified = ipVerified,
                    ipNotice = ipNotice,
                    clientName = matched?.name,
                ))
            }
            path == listOf("presence") && method == "DELETE" -> {
                val clientId = queryParam(call.query, "clientId")
                presence.remove(clientId)
                Reply(204, JSON)
            }
            path.size == 2 && path[0] == "menus" && method == "GET" -> {
                val menu = menus.read(path[1]) ?: return error(404, "There is no menu '${path[1]}'")
                json(200, MenuReply(path[1], Wire.tree(menu)))
            }
            path.size == 2 && path[0] == "menus" && method == "PUT" -> {
                val menu = menuOf(call) ?: return error(400, "The request has no 'menu' object")
                val [decision, matched] = evaluateAccess(who)
                when (decision) {
                    EditorGuard.SaveDecision.ReadOnly -> error(403, "This link is read-only")
                    EditorGuard.SaveDecision.PermissionRevoked -> error(403, "${who.owner} no longer has permission to save menus")
                    is EditorGuard.SaveDecision.Hold -> hold(path[1], menu, who, decision.reason, matched?.name)
                    EditorGuard.SaveDecision.Allow -> when (val result = menus.save(path[1], menu, reload = who.autoReload)) {
                        is EditorMenus.Result.Refused -> error(422, result.reason)
                        is EditorMenus.Result.Ok -> {
                            val actor = matched?.name ?: who.owner
                            val byNote = if (actor != who.owner) "$actor (via ${who.owner}'s link)" else actor
                            log(byNote, if (result.reloaded) "saves and reloads menu '${path[1]}'" else "saves menu '${path[1]}' (pending reload)")
                            onSaved(actor, path[1], result.reloaded, result.problems)
                            json(200, Saved(result.problems, result.reloaded))
                        }
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
                if (who.shared || who.readOnly) return error(403, "Live catalog previews are only available on admin edit links")
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
                val params = parseQuery(call.query) ?: return error(400, "Malformed query parameter")
                val address = params["url"] ?: params["player"]?.let { name ->
                    if (!PLAYER_NAME.matches(name)) return error(400, "Not a valid player name")
                    plugin.heads.skinUrl(name) ?: return error(404, "No skin found for '$name'")
                }
                val url = address?.let(::skinAddress) ?: return error(400, "Only skins on textures.minecraft.net can be fetched")
                val bytes = fetchSkin(url) ?: return error(404, "The skin could not be fetched")
                Reply(200, "image/png", bytes, cache = "max-age=86400")
            }
            path == listOf("game-file") && method == "GET" -> {
                val params = parseQuery(call.query) ?: return error(400, "Malformed query parameter")
                val name = params["name"]
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

    /** Checks the menu and keeps it in [PendingEdits] for an admin; nothing is written to disk. */
    private fun hold(name: String, menu: ConfigObject, who: Caller, reason: String, matchedName: String?): Reply = when (val checked = menus.check(name, menu)) {
        is EditorMenus.Result.Refused -> error(422, checked.reason)
        is EditorMenus.Result.Ok -> {
            val scan = EditorGuard.scanPrivilegedActions(menu)
            if (!scan.scannedAll) {
                error(422, "Menu structure is too deeply nested or large to inspect for privileged actions")
            } else {
                val senderLabel = matchedName ?: who.from
                val edit = pending.add(
                    from = senderLabel,
                    name = name,
                    menu = menu,
                    problems = checked.problems,
                    clientIp = who.clientIp,
                    reason = reason,
                    privileged = scan.actions,
                    privilegedTotal = scan.totalFound,
                    privilegedTruncated = scan.truncated,
                )
                val privNote = if (scan.totalFound > 0) " [${scan.totalFound} privileged action(s)]" else ""
                log(senderLabel, "held an edit of menu '$name' (#${edit.id}, $reason)$privNote")
                onHeld(edit)
                json(200, Held(checked.problems, held = true, id = edit.id, reason = reason))
            }
        }
    }

    private fun parseQuery(query: String?): Map<String, String>? {
        if (query.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (entry in query.split('&')) {
            val eq = entry.indexOf('=').takeIf { it > 0 } ?: continue
            val value = runCatching { URLDecoder.decode(entry.substring(eq + 1), Charsets.UTF_8) }.getOrNull() ?: return null
            result[entry.substring(0, eq)] = value
        }
        return result
    }

    private fun queryParam(query: String?, key: String): String? = parseQuery(query)?.get(key)

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
