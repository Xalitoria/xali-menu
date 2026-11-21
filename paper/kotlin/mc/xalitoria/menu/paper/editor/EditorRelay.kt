package mc.xalitoria.menu.paper.editor

import mc.xalitoria.menu.paper.XaliMenu
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Connects the web editor through a relay (`menu/relay`), for a server that only makes outbound connections. The plugin opens a
 * session at the relay, polls for work, and posts encrypted answers.
 *
 * Each session starts from a 12-character base64url code ([RelayCrypto.newCode]) carried only in the URL fragment (`#<code>`). Both
 * ends derive an opaque 22-character session id and a 256-bit AES-GCM key via PBKDF2-HMAC-SHA256 ([RelayCrypto.derive]), so the relay
 * never sees the code, the key, or any plaintext menus or resource-pack data.
 *
 * Admin edit sessions (`shared = false`, from `/xmenu edit`) sync saved menus directly to `plugins/XaliMenu/menus/` (without reloading
 * live unless `autoReload = true`). Shared sessions (`shared = true`, from `/xmenu share`) hold saves in [PendingEdits] for approval.
 */
internal class EditorRelay(private val plugin: XaliMenu, private val api: EditorApi, private val now: () -> Long = System::currentTimeMillis) {
    /** One active relay session (admin edit or shared). */
    class Share(
        val owner: String,
        val id: String,
        val secret: String,
        val key: SecretKeySpec,
        val expires: Long,
        val readOnly: Boolean = false,
        val shared: Boolean = true,
        val autoReload: Boolean = false,
        val initialMenu: String? = null,
    ) {
        @Volatile var running = true

        /** The requests answered lately, so that a copy sent again is not run twice. */
        val seen: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    }

    sealed interface Started {
        data class Ok(val link: String, val share: Share) : Started
        data class Failed(val reason: String) : Started
    }

    private val shares = ConcurrentHashMap<String, Share>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "XaliMenu-relay").also { it.isDaemon = true } }

    /** The sessions that are running. */
    fun active(): List<Share> = shares.values.filter { it.running }

    /** Opens an encrypted session for [owner] at the relay of the config, and starts answering through it. */
    fun start(
        owner: String,
        menu: String?,
        readOnly: Boolean = false,
        shared: Boolean = true,
        autoReload: Boolean = false,
    ): Started {
        val settings = plugin.config.data.editor
        val relay = settings.relay.url.trim().trimEnd('/')
        if (relay.isEmpty()) return Started.Failed("editor.relay.url is not set in config.yml")
        val minutes = settings.sessionMinutes.coerceAtLeast(1)
        var lastError = "could not open a relay session"
        for (attempt in 1..2) {
            val code = RelayCrypto.newCode()
            val derived = RelayCrypto.derive(code)
            val created = try {
                val payload = Wire.write(RelayCreateRequest(ttl = minutes * 60, id = derived.sessionId))
                val request = HttpRequest.newBuilder(URI("$relay/s")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .also { if (settings.relay.token.isNotBlank()) it.header("Authorization", "Bearer ${settings.relay.token}") }
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
                val response = Http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 409 && attempt == 1) continue
                if (response.statusCode() != 201) return Started.Failed("the relay answered ${response.statusCode()}: ${response.body().take(120)}")
                Wire.read<RelaySession>(response.body()) ?: return Started.Failed("the relay did not answer with a session")
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName.orEmpty()
                return Started.Failed(lastError)
            }
            val share = Share(
                owner = owner,
                id = created.id,
                secret = created.secret,
                key = derived.key,
                expires = now() + minutes * 60_000L,
                readOnly = readOnly,
                shared = shared,
                autoReload = autoReload,
                initialMenu = menu,
            )
            shares[share.id] = share
            workers.execute { poll(relay, share) }
            val link = shortLink(settings.site, relay, code)
            return Started.Ok(link, share)
        }
        return Started.Failed(lastError)
    }

    /** Ends the sessions of [owner] (or all when null), optionally filtered by [shared]. @return how many */
    fun stop(owner: String? = null, shared: Boolean? = null): Int {
        val ending = shares.values.filter { (owner == null || it.owner == owner) && (shared == null || it.shared == shared) }
        for (share in ending) end(share, tell = true)
        return ending.size
    }

    private fun end(share: Share, tell: Boolean) {
        share.running = false
        shares.remove(share.id)
        if (!tell) return
        val relay = plugin.config.data.editor.relay.url.trim().trimEnd('/')
        runCatching {
            Http.send(HttpRequest.newBuilder(URI("$relay/s/${share.id}")).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer ${share.secret}").DELETE().build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    // ---- the loop

    private fun poll(relay: String, share: Share) {
        var failures = 0
        while (share.running && now() < share.expires) {
            try {
                val request = HttpRequest.newBuilder(URI("$relay/s/${share.id}/poll?wait=25")).timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer ${share.secret}").GET().build()
                val response = Http.send(request, HttpResponse.BodyHandlers.ofString())
                when (response.statusCode()) {
                    200 -> {
                        failures = 0
                        for (call in Wire.read<List<RelayCall>>(response.body()).orEmpty()) {
                            val id = call.id ?: continue
                            val data = call.data?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() } ?: continue
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
        val sealed = RelayCrypto.seal(share.key, share.id, "a", Wire.write(reply).toByteArray())
        runCatching {
            val request = HttpRequest.newBuilder(URI("$relay/s/${share.id}/answer/$callId")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer ${share.secret}").POST(HttpRequest.BodyPublishers.ofByteArray(sealed)).build()
            Http.send(request, HttpResponse.BodyHandlers.discarding())
        }.onFailure { plugin.logger.warning("An answer could not be sent to the relay: ${it.message}") }
    }

    /** The answer for the request in [data], or null when it is to be ignored (not made with the key, old, or already answered). */
    internal fun handle(share: Share, data: ByteArray): RelayReply? {
        val plain = RelayCrypto.open(share.key, share.id, "q", data) ?: return null
        val request = Wire.read<RelayRequest>(plain.decodeToString()) ?: return null
        val rid = request.rid ?: return null
        val time = request.t ?: return null
        if (abs(now() - time) > FRESH) return null
        synchronized(share.seen) {
            if (!share.seen.add(rid)) return null
            while (share.seen.size > 2000) share.seen.remove(share.seen.first())
        }
        val method = request.method
        val path = request.path
        if (method !in METHODS || path == null || !path.startsWith("/")) return failure(rid, 400, "Bad request")
        val from = if (share.shared) clean(request.from) else share.owner
        val query = path.substringAfter('?', "").ifEmpty { null }
        val call = EditorApi.Call(method!!, path.substringBefore('?'), query, request.body.orEmpty().toByteArray(), request.range)
        val reply = try {
            api.handle(
                call,
                EditorApi.Caller(
                    owner = share.owner,
                    shared = share.shared,
                    from = from,
                    readOnly = share.readOnly,
                    autoReload = share.autoReload,
                    initialMenu = share.initialMenu,
                ),
            )
        } catch (e: Exception) {
            plugin.logger.warning("The editor failed on a relay request for $path: $e")
            return failure(rid, 500, "The server could not answer")
        }
        val bytes = reply.file?.let { if (Files.size(it) <= MAX_ANSWER) Files.readAllBytes(it) else null }
        val tooBig = (reply.file != null && bytes == null) || reply.body.size > MAX_ANSWER
        if (tooBig) return failure(rid, 413, "That is too big to send at once: ask for a part with a Range")
        return RelayReply(rid, reply.status, reply.type, Base64.getEncoder().encodeToString(bytes ?: reply.body))
    }

    private fun failure(rid: String, status: Int, message: String) = RelayReply(rid, status, JSON, Base64.getEncoder().encodeToString(Wire.write(ErrorReply(message)).toByteArray()))

    companion object {
        private const val FRESH = 10 * 60_000L
        private const val MAX_ANSWER = 5 * 1024 * 1024
        private const val JSON = "application/json; charset=utf-8"
        private val METHODS = setOf("GET", "PUT", "POST")

        /** The name a person gave, as plain text: no control characters, at most 32 long. */
        fun clean(name: String?): String = name.orEmpty().filter { !it.isISOControl() && it != '§' && it != '&' }.trim().take(32).ifEmpty { "guest" }

        /** The default relay URL paired with [site] (`https://menu.xali.to/relay`). */
        fun defaultRelay(site: String): String = "${site.trimEnd('/')}/relay"

        /**
         * The short link that opens the editor on a 12-character code session. When [relay] is the site's default relay
         * (`<site>/relay`), the link is just `<site>/#<code>`; for a custom relay, `&relay=...` is appended.
         */
        fun shortLink(site: String, relay: String, code: String): String {
            val cleanSite = site.trimEnd('/')
            val cleanRelay = relay.trimEnd('/')
            if (cleanRelay == defaultRelay(cleanSite)) return "$cleanSite/#$code"
            return "$cleanSite/#$code&relay=${URLEncoder.encode(cleanRelay, Charsets.UTF_8)}"
        }

        /** Legacy explicit link format (`#relay=...&session=...&key=...`), kept for compatibility. */
        fun link(site: String, relay: String, session: String, key: String, menu: String?): String {
            fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)
            return "${site.trimEnd('/')}/#relay=${encode(relay)}&session=$session&key=$key" + (menu?.let { "&menu=${encode(it)}" } ?: "")
        }
    }
}
