package mc.xalitoria.menu.paper.editor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.fileSize
import mc.xalitoria.menu.paper.XaliMenu

/**
 * The endpoint the web editor talks to, when `editor.enabled` is on. It is the JDK's own HTTP server, so nothing is added to
 * the plugin. Every call needs the token of a link from `/xmenu edit` and comes from the editor's site (or from the machine
 * itself, for development); the browser's cross-origin rules do the rest.
 *
 * ```
 * GET  /v1/info                  {plugin, version, owner, menus: [...]}
 * GET  /v1/menus/<name>          {name, menu: {...}}
 * PUT  /v1/menus/<name>          {menu: {...}}  ->  {problems: [...]}  or 422 {error}
 * POST /v1/menus/<name>/check    {menu: {...}}  ->  {problems: [...]}  or 422 {error}
 * GET  /v1/pack                  the resource pack, as a zip
 * GET  /v1/glyphs                {glyphs: [{name, char, plugin}]}
 * POST /v1/items                 {materials: [...]}  ->  {items: {material: {material, itemModel, customModelData}}}
 * ```
 */
internal class EditorServer(private val plugin: XaliMenu, val sessions: EditorSessions) {
    val menus = EditorMenus(plugin)
    private val packs = ResourcePacks(plugin)
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
            if (origin != null && !allowed(origin)) return reply(exchange, 403, error("This site may not use the editor endpoint"))
            if (origin != null) {
                exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
                exchange.responseHeaders.add("Vary", "Origin")
            }
            if (exchange.requestMethod == "OPTIONS") {
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type")
                exchange.responseHeaders.add("Access-Control-Allow-Private-Network", "true")
                exchange.responseHeaders.add("Access-Control-Max-Age", "600")
                return reply(exchange, 204, null)
            }
            val token = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            val owner = sessions.owner(token) ?: return reply(exchange, 401, error("The link has expired"))
            route(exchange, owner)
        } catch (e: Exception) {
            plugin.logger.warning("The editor endpoint failed on ${exchange.requestURI}: $e")
            runCatching { reply(exchange, 500, error("The server could not answer: ${e.message}")) }
        } finally {
            exchange.close()
        }
    }

    private fun route(exchange: HttpExchange, owner: String) {
        val path = exchange.requestURI.path.removePrefix("/v1").trim('/').split('/')
        val method = exchange.requestMethod
        when {
            path == listOf("info") && method == "GET" -> reply(exchange, 200, Json.write(mapOf(
                "plugin" to "XaliMenu", "version" to plugin.pluginMeta.version, "owner" to owner,
                "menus" to menus.names(), "pack" to (packs.pack() != null),
            )))
            path.size == 2 && path[0] == "menus" && method == "GET" -> {
                val menu = menus.read(path[1]) ?: return reply(exchange, 404, error("There is no menu '${path[1]}'"))
                reply(exchange, 200, Json.write(mapOf("name" to path[1], "menu" to menu.unwrapped())))
            }
            path.size == 2 && path[0] == "menus" && method == "PUT" -> {
                val menu = body(exchange) ?: return
                log(owner, "saves menu '${path[1]}'")
                answer(exchange, menus.save(path[1], menu))
            }
            path.size == 3 && path[0] == "menus" && path[2] == "check" && method == "POST" -> {
                val menu = body(exchange) ?: return
                answer(exchange, menus.check(path[1], menu))
            }
            path == listOf("pack") && method == "GET" -> {
                val file = packs.pack() ?: return reply(exchange, 404, error("There is no resource pack"))
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.sendResponseHeaders(200, file.fileSize())
                Files.copy(file, exchange.responseBody)
            }
            path == listOf("glyphs") && method == "GET" -> reply(exchange, 200, Json.write(mapOf("glyphs" to packs.glyphs())))
            path == listOf("items") && method == "POST" -> {
                val request = Json.read(exchange.requestBody.readNBytes(MAX_BODY).decodeToString())
                val materials = (request.unwrapped()["materials"] as? List<*>).orEmpty().filterIsInstance<String>().take(500)
                reply(exchange, 200, Json.write(mapOf("items" to packs.items(materials))))
            }
            else -> reply(exchange, 404, error("Unknown request"))
        }
    }

    private fun answer(exchange: HttpExchange, result: EditorMenus.Result) = when (result) {
        is EditorMenus.Result.Ok -> reply(exchange, 200, Json.write(mapOf("problems" to result.problems)))
        is EditorMenus.Result.Refused -> reply(exchange, 422, error(result.reason))
    }

    /** The `menu` object of the request, or null after answering that it is not there. */
    private fun body(exchange: HttpExchange): com.typesafe.config.ConfigObject? {
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) { reply(exchange, 413, error("The menu is too big")); return null }
        val menu = try {
            Json.read(bytes.decodeToString())["menu"] as? com.typesafe.config.ConfigObject
        } catch (e: Exception) { null }
        if (menu == null) reply(exchange, 400, error("The request has no 'menu' object"))
        return menu
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String?) {
        if (body == null) { exchange.sendResponseHeaders(status, -1); return }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun error(message: String) = Json.write(mapOf("error" to message))

    private fun log(owner: String, what: String) = plugin.logger.info("$owner $what in the editor")

    /** The editor's site, and the machine itself (for development); nothing else. */
    private fun allowed(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val site = runCatching { URI(plugin.config.data.editor.site) }.getOrNull()
        if (site != null && uri.scheme == site.scheme && uri.host == site.host && uri.port == site.port) return true
        return uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")
    }

    private companion object {
        const val MAX_BODY = 2 * 1024 * 1024
    }
}
