package mc.xalitoria.menu.paper.editor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mc.xalitoria.menu.paper.XaliMenu
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.fileSize

/**
 * The endpoint the web editor talks to, when `editor.enabled` is on. It is the JDK's own HTTP server, so nothing is added to
 * the plugin. Every call needs the token of a link from `/xmenu edit` and comes from the editor's site (or from the machine
 * itself, for development); the browser's cross-origin rules do the rest.
 *
 * ```
 * GET  /v1/info                  {plugin, version, owner, menus: [...], pack, catalogs: {type: {values: {name: example}, settings: {key: example}}}}
 * GET  /v1/menus/<name>          {name, menu: {...}}
 * PUT  /v1/menus/<name>          {menu: {...}}  ->  {problems: [...]}  or 422 {error}
 * POST /v1/menus/<name>/check    {menu: {...}}  ->  {problems: [...]}  or 422 {error}
 * GET  /v1/pack                  the resource pack, as a zip
 * GET  /v1/catalog               {vanilla: [...], custom: {prefix: [...]}}  every item a menu can use
 * GET  /v1/glyphs                {glyphs: [{name, char, plugin}]}
 * POST /v1/items                 {materials: [...]}  ->  {items: {material: {material, itemModel, customModelData, maxDamage}}}
 * POST /v1/colors               {potions: [{type, effects: [{type, amplifier}]}]}  ->  {colors: [rgb or null]}
 * POST /v1/elements              {catalog: {type, ...}, sort, descending}  ->  {elements: [{name, ..., index}], total}  what the catalog lists now, for the link's player
 * GET  /v1/skin?url=<address>    the skin png at a textures.minecraft.net address (the editor's browser cannot read it from there)
 * GET  /v1/game-file?name=font/unifont.zip   a zip of the game's font folder (the fallback font), fetched from Mojang once and kept
 * ```
 */
internal class EditorServer(private val plugin: XaliMenu, val sessions: EditorSessions, val pending: PendingEdits = PendingEdits()) {
    val menus = EditorMenus(plugin)
    val api = EditorApi(plugin, menus, pending)
    private var server: HttpServer? = null

    /** Starts listening. @return null if it does, otherwise why it could not */
    fun start(): String? {
        val settings = plugin.config.data.editor
        return try {
            val http = HttpServer.create(InetSocketAddress(settings.host, settings.port), 0)
            http.createContext("/v1") { exchange -> handle(exchange) }
            http.executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "XaliMenu-editor").also { it.isDaemon = true } }
            http.start()
            server = http
            null
        } catch (e: Exception) {
            e.message ?: e::class.simpleName.orEmpty()
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        sessions.clear()
    }

    val running: Boolean get() = server != null

    // ---- one request

    private fun handle(exchange: HttpExchange) {
        try {
            val origin = exchange.requestHeaders.getFirst("Origin")
            exchange.responseHeaders.add("Access-Control-Expose-Headers", "Content-Range")
            if (origin != null && !allowed(origin)) return reply(exchange, 403, error("This site may not use the editor endpoint"))
            if (origin != null) {
                exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
                exchange.responseHeaders.add("Vary", "Origin")
            }
            if (exchange.requestMethod == "OPTIONS") {
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type, Range, X-Editor-From")
                if (exchange.requestHeaders.getFirst("Access-Control-Request-Private-Network").equals("true", ignoreCase = true)) {
                    exchange.responseHeaders.add("Access-Control-Allow-Private-Network", "true")
                }
                exchange.responseHeaders.add("Access-Control-Max-Age", "600")
                return reply(exchange, 204, null)
            }
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val token = auth?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
            val session = sessions.session(token) ?: return reply(exchange, 401, error("The link has expired"))
            val bytes = exchange.requestBody.readNBytes(EditorApi.MAX_BODY + 1)
            if (bytes.size > EditorApi.MAX_BODY) return reply(exchange, 413, error("The request is too big"))
            val path = exchange.requestURI.path.removePrefix("/v1")
            val from = if (session.shared) EditorRelay.clean(exchange.requestHeaders.getFirst("X-Editor-From") ?: session.owner) else session.owner
            val clientIp = EditorGuard.resolveClientIp(
                remoteIp = exchange.remoteAddress?.address?.hostAddress,
                cfConnectingIp = exchange.requestHeaders.getFirst("CF-Connecting-IP"),
                forwardedFor = exchange.requestHeaders.getFirst("X-Forwarded-For"),
                trustProxy = plugin.config.data.editor.trustProxy,
            )
            val caller = EditorApi.Caller(
                owner = session.owner,
                shared = session.shared,
                from = from,
                readOnly = session.readOnly,
                autoReload = session.autoReload,
                initialMenu = session.initialMenu,
                ownerIp = session.ownerIp,
                ownerUuid = session.ownerUuid,
                clientIp = clientIp,
            )
            send(exchange, api.handle(EditorApi.Call(exchange.requestMethod, path, exchange.requestURI.rawQuery, bytes, exchange.requestHeaders.getFirst("Range")), caller))
        } catch (e: Exception) {
            plugin.logger.warning("The editor endpoint failed on ${exchange.requestURI}: $e")
            runCatching { reply(exchange, 500, error("The server could not answer")) }
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, reply: EditorApi.Reply) {
        exchange.responseHeaders.add("Content-Type", reply.type)
        reply.cache?.let { exchange.responseHeaders.add("Cache-Control", it) }
        for (header in reply.headers) exchange.responseHeaders.add(header.key, header.value)
        if (reply.file != null) {
            exchange.sendResponseHeaders(reply.status, reply.file.fileSize())
            Files.copy(reply.file, exchange.responseBody)
        } else if (reply.status == 204) {
            exchange.sendResponseHeaders(204, -1)
        } else {
            exchange.sendResponseHeaders(reply.status, reply.body.size.toLong())
            exchange.responseBody.write(reply.body)
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String?) {
        if (body == null) { exchange.sendResponseHeaders(status, -1); return }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun error(message: String) = Wire.write(ErrorReply(message))

    /** The editor's site, and localhost (only when listening on loopback for local development); nothing else. */
    private fun allowed(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val editorCfg = plugin.config.data.editor
        val site = runCatching { URI(editorCfg.site) }.getOrNull()
        if (site != null && uri.scheme == site.scheme && uri.host == site.host && uri.port == site.port) return true
        return EditorGuard.isLoopback(editorCfg.host) && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")
    }
}
