package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Pushes a menu from this server to another XaliMenu server using that target server's `/xmenu edit` or `/xmenu share`
 * link (12-character relay code, full `#<code>` URL, or direct `#server=...&token=...` URL). Works over both the end-to-end
 * encrypted relay and direct HTTP endpoints, and respects the target server's validation, IP policy and pending edits.
 */
internal object EditorPush {
    sealed interface Target {
        data class Relay(val relayUrl: String, val code: String) : Target
        data class Direct(val serverUrl: String, val token: String) : Target
    }

    sealed interface Result {
        data class Saved(val reloaded: Boolean, val problems: List<String>) : Result
        data class Held(val id: Int, val reason: String?, val problems: List<String>) : Result
        data class Failed(val reason: String) : Result
    }

    private val random = SecureRandom()

    /** Parses a bare 12-character code, a `#<code>` relay URL, or a `#server=...&token=...` direct editor URL. */
    fun parseTarget(raw: String, defaultRelay: String): Target? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val fragment = text.substringAfter('#', text).trim()
        if (fragment.isEmpty()) return null
        val parts = fragment.split('&')
        val first = parts[0]
        if ('=' !in first && RelayCrypto.CODE_PATTERN.matches(first)) {
            val params = parseParams(parts.drop(1))
            val relay = params["relay"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: defaultRelay.trim().trimEnd('/')
            if (!validHttpUrl(relay)) return null
            return Target.Relay(relay, first)
        }
        val params = parseParams(parts)
        val server = params["server"]?.trim()?.trimEnd('/')
        val token = params["token"]?.trim()
        if (!server.isNullOrEmpty() && !token.isNullOrEmpty() && validDirectUrl(server)) {
            return Target.Direct(server, token)
        }
        return null
    }

    /** Sends [menu] under [name] to [target] as [from]. Blocking network call — run off the region thread. */
    fun push(target: Target, name: String, menu: ConfigObject, from: String): Result {
        if (!EditorMenus.NAME.matches(name)) return Result.Failed("'$name' is not a valid menu name")
        val payload = Wire.write(MenuRequest(Wire.tree(menu) as? kotlinx.serialization.json.JsonObject))
        return when (target) {
            is Target.Relay -> pushRelay(target, name, payload, from)
            is Target.Direct -> pushDirect(target, name, payload, from)
        }
    }

    private fun pushRelay(target: Target.Relay, name: String, body: String, from: String): Result {
        return try {
            val derived = RelayCrypto.derive(target.code)
            val rid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also(random::nextBytes))
            val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
            val request = RelayRequest(
                rid = rid,
                t = System.currentTimeMillis(),
                method = "PUT",
                path = "/menus/$encodedName",
                from = EditorRelay.clean(from),
                body = body,
            )
            val sealed = RelayCrypto.seal(derived.key, derived.sessionId, "q", Wire.write(request).toByteArray())
            val httpReq = HttpRequest.newBuilder(URI("${target.relayUrl}/s/${derived.sessionId}/call"))
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofByteArray(sealed))
                .build()
            val response = Http.send(httpReq, HttpResponse.BodyHandlers.ofByteArray())
            when (response.statusCode()) {
                404 -> return Result.Failed("The target link has expired or ended.")
                504 -> return Result.Failed("The target server did not answer in time.")
                !in 200..299 -> return Result.Failed("The target relay answered HTTP ${response.statusCode()}.")
            }
            val plain = RelayCrypto.open(derived.key, derived.sessionId, "a", response.body())
                ?: return Result.Failed("Could not decrypt the target server's reply (wrong link code?).")
            val reply = Wire.read<RelayReply>(plain.decodeToString())
                ?: return Result.Failed("The target server returned an unreadable relay reply.")
            if (reply.rid != rid) return Result.Failed("Mismatched reply id from target server.")
            val replyBytes = runCatching { Base64.getDecoder().decode(reply.body) }.getOrNull()
                ?: return Result.Failed("Invalid reply payload from target server.")
            interpretResponse(reply.status, replyBytes.decodeToString())
        } catch (e: Exception) {
            Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun pushDirect(target: Target.Direct, name: String, body: String, from: String): Result {
        if (!validDirectUrl(target.serverUrl)) {
            return Result.Failed("Direct push requires https:// so the bearer token is not sent in cleartext (http:// is only allowed for localhost/loopback).")
        }
        return try {
            val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
            val httpReq = HttpRequest.newBuilder(URI("${target.serverUrl}/v1/menus/$encodedName"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer ${target.token}")
                .header("Content-Type", "application/json")
                .header("X-Editor-From", EditorRelay.clean(from))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = Http.send(httpReq, HttpResponse.BodyHandlers.ofString())
            interpretResponse(response.statusCode(), response.body())
        } catch (e: Exception) {
            Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun interpretResponse(status: Int, json: String): Result {
        if (status == 401) return Result.Failed("The target link has expired.")
        if (status !in 200..299) {
            val err = Wire.read<ErrorReply>(json)?.error
            return Result.Failed(err ?: "The target server answered HTTP $status.")
        }
        val held = Wire.read<Held>(json)
        if (held != null && held.held) return Result.Held(held.id, held.reason, held.problems)
        val saved = Wire.read<Saved>(json)
        if (saved != null) return Result.Saved(saved.reloaded, saved.problems)
        return Result.Saved(reloaded = false, problems = emptyList())
    }

    private fun parseParams(entries: List<String>): Map<String, String> = entries.mapNotNull { entry ->
        val eq = entry.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        val key = entry.substring(0, eq)
        val value = runCatching { URLDecoder.decode(entry.substring(eq + 1), Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
        key to value
    }.toMap()

    private fun validHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    /** Direct pushes send a Bearer token, so require HTTPS everywhere except loopback addresses. */
    private fun validDirectUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        if (uri.userInfo != null) return false
        if (uri.scheme == "https") return true
        return uri.scheme == "http" && (host.equals("localhost", ignoreCase = true) || EditorGuard.isLoopback(host))
    }
}
