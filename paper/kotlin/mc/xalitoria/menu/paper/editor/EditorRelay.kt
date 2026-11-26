package mc.xalitoria.menu.paper.editor

import mc.xalitoria.menu.paper.XaliMenu
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Connects the web editor through a relay (`menu/relay`), for a server that only makes outbound connections. The plugin opens a
 * session at the relay and receives encrypted requests over a Hibernatable WebSocket (when the relay advertises `ws: true`,
 * such as the Cloudflare Worker) or HTTP long-polling (for self-hosted `relay.js`).
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
        val ownerIp: String? = null,
        val ownerUuid: UUID? = null,
    ) {
        @Volatile var running = true
        @Volatile var ws: WebSocket? = null
        val wsSendLock = Any()
        val stopSignal = CountDownLatch(1)

        /** Request ids answered within [FRESH], mapped to their timestamp, so replays are dropped. */
        val seen: MutableMap<String, Long> = java.util.Collections.synchronizedMap(LinkedHashMap())
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
        ownerIp: String? = null,
        ownerUuid: UUID? = null,
    ): Started {
        val settings = plugin.config.data.editor
        val relay = settings.relay.url.trim().trimEnd('/')
        if (relay.isEmpty()) return Started.Failed("editor.relay.url is not set in config.yml")
        val relayUri = runCatching { URI(relay) }.getOrNull()
        if (relayUri == null || (relayUri.scheme != "https" && relayUri.scheme != "http") || relayUri.host.isNullOrBlank()) {
            return Started.Failed("editor.relay.url must be an http:// or https:// address")
        }
        val current = now()
        shares.values.filter { !it.running || it.expires <= current }.forEach { end(it, tell = false) }
        if (shares.size >= MAX_SHARES) return Started.Failed("too many active relay sessions ($MAX_SHARES); run /xmenu edit stop or /xmenu share stop")
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
                ownerIp = EditorGuard.normalizeIp(ownerIp),
                ownerUuid = ownerUuid,
            )
            shares[share.id] = share
            if (created.ws) {
                workers.execute { runWebSocket(relay, share) }
            } else {
                workers.execute { poll(relay, share) }
            }
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
        share.stopSignal.countDown()
        shares.remove(share.id)
        share.ws?.let { socket ->
            share.ws = null
            runCatching { synchronized(share.wsSendLock) { socket.sendClose(WebSocket.NORMAL_CLOSURE, "ended").get(2, TimeUnit.SECONDS) } }
        }
        if (!tell) return
        val relay = plugin.config.data.editor.relay.url.trim().trimEnd('/')
        runCatching {
            Http.send(HttpRequest.newBuilder(URI("$relay/s/${share.id}")).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer ${share.secret}").DELETE().build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    // ---- Hibernatable WebSocket loop (Cloudflare Workers)

    private fun runWebSocket(relay: String, share: Share) {
        val endpoint = wsUri(relay, share.id)
        var failures = 0
        while (share.running && now() < share.expires) {
            val closed = CountDownLatch(1)
            val listener = object : WebSocket.Listener {
                private val buffer = StringBuilder()
                private var oversized = false

                override fun onOpen(webSocket: WebSocket) {
                    failures = 0
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    if (!oversized) {
                        if (buffer.length + data.length > MAX_WS_FRAME) {
                            oversized = true
                            buffer.setLength(0)
                        } else {
                            buffer.append(data)
                        }
                    }
                    if (last) {
                        val drop = oversized
                        oversized = false
                        val text = if (drop) "" else buffer.toString()
                        buffer.setLength(0)
                        if (!drop && text != "pong") {
                            Wire.read<RelayCall>(text)?.let { call ->
                                val id = call.id?.takeIf { CALL_ID.matches(it) }
                                if (id != null) workers.execute { dispatchWsCall(relay, share, id, call, webSocket) }
                            }
                        }
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*> {
                    closed.countDown()
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    closed.countDown()
                }
            }

            val socket = try {
                Http.newWebSocket()
                    .header("Authorization", "Bearer ${share.secret}")
                    .buildAsync(endpoint, listener)
                    .get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                val handshake = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<WebSocketHandshakeException>().firstOrNull()
                val status = handshake?.response?.statusCode()
                if (status == 404 || status == 401) break
                if (status == 426 || status == 501) {
                    poll(relay, share)
                    return
                }
                if (++failures >= 10) {
                    plugin.logger.warning("The relay session of ${share.owner} ended: WebSocket could not connect (${e.message})")
                    break
                }
                val delayMs = minOf(10_000L, 1_000L shl (failures - 1).coerceAtMost(3))
                if (share.stopSignal.await(delayMs, TimeUnit.MILLISECONDS)) break
                continue
            }

            share.ws = socket
            // Keep the socket alive across NATs; Cloudflare answers "ping" with "pong" without waking the Durable Object.
            while (share.running && now() < share.expires) {
                if (closed.await(WS_PING_SECONDS, TimeUnit.SECONDS)) break
                if (!share.running || now() >= share.expires) break
                val pingOk = runCatching {
                    synchronized(share.wsSendLock) { socket.sendText("ping", true).get(10, TimeUnit.SECONDS) }
                }.isSuccess
                if (!pingOk) break
            }
            if (share.ws === socket) share.ws = null
            runCatching { socket.abort() }
            if (share.running && now() < share.expires) {
                if (++failures >= 10) break
                if (share.stopSignal.await(minOf(10_000L, 1_000L * failures), TimeUnit.MILLISECONDS)) break
            }
        }
        if (shares[share.id] === share) end(share, tell = share.running)
        share.running = false
    }

    private fun dispatchWsCall(relay: String, share: Share, callId: String, call: RelayCall, ws: WebSocket) {
        if (!CALL_ID.matches(callId)) return
        var rawIp = call.ip
        val data = if (call.fetch) {
            runCatching {
                val req = HttpRequest.newBuilder(URI("$relay/s/${share.id}/call/$callId")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer ${share.secret}").GET().build()
                val res = Http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                res.body().use { stream ->
                    if (res.statusCode() != 200) return@runCatching null
                    if (rawIp.isNullOrBlank()) rawIp = res.headers().firstValue("x-relay-client-ip").orElse(null)
                    readBounded(stream, MAX_RELAY_BODY)
                }
            }.getOrNull() ?: return
        } else {
            decodeCallData(call.data) ?: return
        }
        val clientIp = trustedRelayIp(relay, plugin.config.data.editor.relay.trustIp, rawIp)
        answer(relay, share, callId, data, ws, clientIp)
    }

    // ---- HTTP long-polling loop (self-hosted relay.js fallback)

    private fun poll(relay: String, share: Share) {
        var failures = 0
        while (share.running && now() < share.expires) {
            try {
                val request = HttpRequest.newBuilder(URI("$relay/s/${share.id}/poll?wait=25")).timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer ${share.secret}").GET().build()
                val response = Http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                val body = response.body().use { stream ->
                    if (status == 200) readBounded(stream, MAX_POLL_BODY) else null
                }
                when (status) {
                    200 -> {
                        failures = 0
                        if (body == null) {
                            plugin.logger.warning("Dropped oversized polling response from relay (exceeded $MAX_POLL_BODY bytes)")
                            continue
                        }
                        val trustIp = plugin.config.data.editor.relay.trustIp
                        for (call in Wire.read<List<RelayCall>>(body.decodeToString()).orEmpty()) {
                            val id = call.id?.takeIf { CALL_ID.matches(it) } ?: continue
                            val data = decodeCallData(call.data) ?: continue
                            val clientIp = trustedRelayIp(relay, trustIp, call.ip)
                            workers.execute { answer(relay, share, id, data, null, clientIp) }
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

    /** Runs the request in [data] and returns the encrypted answer over [ws] (when small enough) or via HTTP POST. */
    private fun answer(relay: String, share: Share, callId: String, data: ByteArray, ws: WebSocket?, clientIp: String? = null) {
        if (!CALL_ID.matches(callId)) return
        val reply = handle(share, data, clientIp) ?: return
        val sealed = RelayCrypto.seal(share.key, share.id, "a", Wire.write(reply).toByteArray())
        if (ws != null && sealed.size <= WS_ANSWER_MAX) {
            val frame = Wire.write(RelayWsAnswer(callId, Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)))
            val sent = runCatching {
                synchronized(share.wsSendLock) { ws.sendText(frame, true).get(15, TimeUnit.SECONDS) }
            }.isSuccess
            if (sent) return
        }
        runCatching {
            val request = HttpRequest.newBuilder(URI("$relay/s/${share.id}/answer/$callId")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer ${share.secret}").POST(HttpRequest.BodyPublishers.ofByteArray(sealed)).build()
            Http.send(request, HttpResponse.BodyHandlers.discarding())
        }.onFailure { plugin.logger.warning("An answer could not be sent to the relay: ${it.message}") }
    }

    /** The answer for the request in [data], or null when it is to be ignored (oversized, not made with the key, old, or already answered). */
    internal fun handle(share: Share, data: ByteArray, clientIp: String? = null): RelayReply? {
        if (data.size > MAX_RELAY_BODY) return null
        val plain = RelayCrypto.open(share.key, share.id, "q", data) ?: return null
        val request = Wire.read<RelayRequest>(plain.decodeToString()) ?: return null
        val rid = request.rid?.takeIf { it.length in 1..64 } ?: return null
        val time = request.t ?: return null
        val current = now()
        if (abs(current - time) > FRESH) return null
        synchronized(share.seen) {
            val iter = share.seen.entries.iterator()
            while (iter.hasNext()) {
                if (current - iter.next().value > FRESH) iter.remove() else break
            }
            if (share.seen.containsKey(rid)) return null
            share.seen[rid] = current
            while (share.seen.size > MAX_SEEN) share.seen.remove(share.seen.keys.first())
        }
        val method = request.method
        val path = request.path
        if (method !in METHODS || path == null || !path.startsWith("/")) return failure(rid, 400, "Bad request")
        val bodyBytes = request.body.orEmpty().toByteArray()
        if (bodyBytes.size > EditorApi.MAX_BODY) return failure(rid, 413, "The request is too big")
        val from = if (share.shared) clean(request.from) else share.owner
        val query = path.substringAfter('?', "").ifEmpty { null }
        val call = EditorApi.Call(method!!, path.substringBefore('?'), query, bodyBytes, request.range)
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
                    ownerIp = share.ownerIp,
                    ownerUuid = share.ownerUuid,
                    clientIp = EditorGuard.normalizeIp(clientIp),
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
        private const val FRESH = 2 * 60_000L
        private const val MAX_SEEN = 4096
        private const val MAX_SHARES = 20
        private const val MAX_WS_FRAME = 1024 * 1024
        internal const val MAX_RELAY_BODY = EditorApi.MAX_BODY + 256 * 1024
        internal const val MAX_POLL_BODY = 4 * 1024 * 1024
        private const val MAX_ENCODED_RELAY_BODY = ((MAX_RELAY_BODY + 2) / 3) * 4
        private const val MAX_ANSWER = 5 * 1024 * 1024
        private const val WS_ANSWER_MAX = 512 * 1024
        private const val WS_PING_SECONDS = 55L
        private const val JSON = "application/json; charset=utf-8"
        private val METHODS = setOf("GET", "PUT", "POST", "DELETE")
        val CALL_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** Reads at most [maxBytes] from [stream], returning null without buffering the rest if the stream exceeds the cap. */
        internal fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
            val bytes = stream.readNBytes(maxBytes + 1)
            return bytes.takeIf { it.size <= maxBytes }
        }

        /** Base64url-decodes a relay call payload only when its encoded and decoded sizes fit within [MAX_RELAY_BODY]. */
        internal fun decodeCallData(encoded: String?): ByteArray? {
            if (encoded == null || encoded.length > MAX_ENCODED_RELAY_BODY) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
            return bytes.takeIf { it.size <= MAX_RELAY_BODY }
        }

        /**
         * Returns normalized [rawIp] only when [trustIp] is explicitly enabled AND [relayUrl] uses `https://` or
         * loopback (`http://127.0.0.1` / `localhost` / `[::1]`). Unauthenticated metadata from untrusted or cleartext
         * relays is never accepted for IP-gated save authorization.
         */
        internal fun trustedRelayIp(relayUrl: String, trustIp: Boolean, rawIp: String?): String? {
            if (!trustIp || rawIp.isNullOrBlank()) return null
            val uri = runCatching { URI(relayUrl) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val safeTransport = uri.scheme == "https" || (uri.scheme == "http" && (host.equals("localhost", ignoreCase = true) || EditorGuard.isLoopback(host)))
            if (!safeTransport) return null
            return EditorGuard.normalizeIp(rawIp)
        }

        /** Converts an HTTP(S) relay base URL into its WebSocket session endpoint (`wss://.../s/<id>/ws`). */
        fun wsUri(relay: String, sessionId: String): URI {
            val base = relay.trimEnd('/')
            val wsBase = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return URI("$wsBase/s/$sessionId/ws")
        }

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
    }
}
