package mc.xalitoria.menu.paper.editor

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import mc.xalitoria.menu.paper.XaliMenu

/**
 * Shares the editor through a relay (`menu/relay`), for a server that cannot be reached from the editor's page. The plugin only connects
 * out: it opens a session at the relay, asks it for work, and answers each request there. What goes through is encrypted with a key that is
 * only in the link, so the relay moves bytes it cannot read; a request that is not made with the key, is old, or has been seen is dropped.
 *
 * Everyone with the link reads what the editor reads, and what they save is held for an admin ([PendingEdits]).
 */
internal class EditorRelay(private val plugin: XaliMenu, private val api: EditorApi, private val now: () -> Long = System::currentTimeMillis) {
    /** One shared link. */
    class Share(val owner: String, val id: String, val secret: String, val key: SecretKeySpec, val expires: Long) {
        @Volatile var running = true
        /** The requests answered lately, so that a copy sent again is not run twice. */
        val seen: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    }

    sealed interface Started {
        data class Ok(val link: String, val share: Share) : Started
        data class Failed(val reason: String) : Started
    }

    private val shares = ConcurrentHashMap<String, Share>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "XaliMenu-relay").also { it.isDaemon = true } }

    /** The shares that are running. */
    fun active(): List<Share> = shares.values.filter { it.running }

    /** Opens a session for [owner] at the relay of the config, and starts answering through it. */
    fun start(owner: String, menu: String?): Started {
        val settings = plugin.config.data.editor
        val relay = settings.relay.url.trim().trimEnd('/')
        if (relay.isEmpty()) return Started.Failed("editor.relay.url is not set in config.yml")
        val minutes = settings.sessionMinutes.coerceAtLeast(1)
        val keyText = RelayCrypto.newKey()
        val share = try {
            val request = HttpRequest.newBuilder(URI("$relay/s")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .also { if (settings.relay.token.isNotBlank()) it.header("Authorization", "Bearer ${settings.relay.token}") }
                .POST(HttpRequest.BodyPublishers.ofString("""{"ttl":${minutes * 60}}""")).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 201) return Started.Failed("the relay answered ${response.statusCode()}: ${response.body().take(120)}")
            val made = Json.read(response.body()).unwrapped()
            Share(owner, made["id"] as String, made["secret"] as String, RelayCrypto.keyOf(keyText), now() + minutes * 60_000L)
        } catch (e: Exception) {
            return Started.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        shares[share.id] = share
        workers.execute { poll(relay, share) }
        val link = link(settings.site, relay, share.id, keyText, menu)
        return Started.Ok(link, share)
    }

    /** Ends the shares of [owner], or all of them when it is null. @return how many */
    fun stop(owner: String? = null): Int {
        val ending = shares.values.filter { owner == null || it.owner == owner }
        for (share in ending) end(share, tell = true)
        return ending.size
    }

    private fun end(share: Share, tell: Boolean) {
        share.running = false
        shares.remove(share.id)
        if (!tell) return
        val relay = plugin.config.data.editor.relay.url.trim().trimEnd('/')
        runCatching {
            http.send(HttpRequest.newBuilder(URI("$relay/s/${share.id}")).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer ${share.secret}").DELETE().build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    // ---- the loop

    private fun poll(relay: String, share: Share) {
        var failures = 0
        while (share.running && now() < share.expires) {
            try {
                val request = HttpRequest.newBuilder(URI("$relay/s/${share.id}/poll?wait=25")).timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer ${share.secret}").GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                when (response.statusCode()) {
                    200 -> {
                        failures = 0
                        val calls = (Json.read("""{"calls":${response.body()}}""").unwrapped()["calls"] as? List<*>).orEmpty()
                        for (call in calls) {
                            val map = call as? Map<*, *> ?: continue
                            val id = map["id"] as? String ?: continue
                            val data = (map["data"] as? String)?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() } ?: continue
                            workers.execute { answer(relay, share, id, data) }
                        }
                    }
                    204 -> failures = 0
                    404 -> break
                    else -> { failures++; Thread.sleep(3000) }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (++failures >= 20) { plugin.logger.warning("The relay share of ${share.owner} ended: the relay could not be reached (${e.message})"); break }
                runCatching { Thread.sleep(3000) }
            }
        }
        if (shares[share.id] === share) end(share, tell = false)
        share.running = false
    }

    /** Runs the request in [data] and posts the answer, if it is one that is to be answered. */
    private fun answer(relay: String, share: Share, callId: String, data: ByteArray) {
        val reply = handle(share, data) ?: return
        val sealed = RelayCrypto.seal(share.key, share.id, "a", Json.write(reply).toByteArray())
        runCatching {
            val request = HttpRequest.newBuilder(URI("$relay/s/${share.id}/answer/$callId")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer ${share.secret}").POST(HttpRequest.BodyPublishers.ofByteArray(sealed)).build()
            http.send(request, HttpResponse.BodyHandlers.discarding())
        }.onFailure { plugin.logger.warning("An answer could not be sent to the relay: ${it.message}") }
    }

    /** The answer for the request in [data], or null when it is to be ignored (not made with the key, old, or already answered). */
    internal fun handle(share: Share, data: ByteArray): Map<String, Any?>? {
        val plain = RelayCrypto.open(share.key, share.id, "q", data) ?: return null
        val request = runCatching { Json.read(plain.decodeToString()).unwrapped() }.getOrNull() ?: return null
        val rid = request["rid"] as? String ?: return null
        val time = (request["t"] as? Number)?.toLong() ?: return null
        if (abs(now() - time) > FRESH) return null
        synchronized(share.seen) {
            if (!share.seen.add(rid)) return null
            while (share.seen.size > 2000) share.seen.remove(share.seen.first())
        }
        val method = request["method"] as? String
        val path = request["path"] as? String
        if (method !in METHODS || path == null || !path.startsWith("/")) return mapOf("rid" to rid, "status" to 400, "type" to JSON, "body" to b64("""{"error":"Bad request"}"""))
        val from = clean(request["from"] as? String)
        val bodyText = (request["body"] as? String).orEmpty()
        val query = path.substringAfter('?', "").ifEmpty { null }
        val call = EditorApi.Call(method!!, path.substringBefore('?'), query, bodyText.toByteArray(), request["range"] as? String)
        val reply = try {
            api.handle(call, EditorApi.Caller(share.owner, shared = true, from = from))
        } catch (e: Exception) {
            plugin.logger.warning("The editor failed on a shared request for $path: $e")
            return mapOf("rid" to rid, "status" to 500, "type" to JSON, "body" to b64(Json.write(mapOf("error" to "The server could not answer"))))
        }
        val bytes = reply.file?.let { if (Files.size(it) <= MAX_ANSWER) Files.readAllBytes(it) else null }
        val tooBig = (reply.file != null && bytes == null) || reply.body.size > MAX_ANSWER
        if (tooBig) return mapOf("rid" to rid, "status" to 413, "type" to JSON, "body" to b64(Json.write(mapOf("error" to "That is too big to send at once: ask for a part with a Range"))))
        return mapOf("rid" to rid, "status" to reply.status, "type" to reply.type, "body" to Base64.getEncoder().encodeToString(bytes ?: reply.body))
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    companion object {
        private const val FRESH = 10 * 60_000L
        private const val MAX_ANSWER = 5 * 1024 * 1024
        private const val JSON = "application/json; charset=utf-8"
        private val METHODS = setOf("GET", "PUT", "POST")

        /** The name a person gave, as plain text: no control characters, at most 32 long. */
        fun clean(name: String?): String = name.orEmpty().filter { !it.isISOControl() && it != '§' && it != '&' }.trim().take(32).ifEmpty { "guest" }

        /** The link that opens the editor on the session. */
        fun link(site: String, relay: String, session: String, key: String, menu: String?): String {
            fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)
            return "${site.trimEnd('/')}/#relay=${encode(relay)}&session=$session&key=$key" + (menu?.let { "&menu=${encode(it)}" } ?: "")
        }
    }
}
