package mc.xalitoria.menu.paper.editor

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The editor's requests to other servers (the relay, Mojang, the skin server), through one client with one connect timeout, instead of a client in each of them.
 * A redirect is only followed when the caller says so: a fetch that is allowed one host (a skin) must not be led to another by the host's answer.
 */
internal object Http {
    private val CONNECT = Duration.ofSeconds(8)
    private val direct = HttpClient.newBuilder().connectTimeout(CONNECT).build()
    private val following = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NORMAL).build()

    /** Sends [request] and reads the answer with [handler]. An I/O failure is thrown. */
    fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, follow: Boolean = false): HttpResponse<T> =
        (if (follow) following else direct).send(request, handler)

    /** A WebSocket builder sharing the client's connection pool and connect timeout. */
    fun newWebSocket(): java.net.http.WebSocket.Builder = direct.newWebSocketBuilder().connectTimeout(CONNECT)

    /**
     * The body of the answer at [address] when it is `200` and not longer than [maxBytes], else null. The body is read only as far as [maxBytes] and one byte more, so an
     * answer that is too big is never held in memory whole.
     */
    fun get(address: URI, timeout: Duration, maxBytes: Int, follow: Boolean = false): ByteArray? {
        val request = HttpRequest.newBuilder(address).timeout(timeout).GET().build()
        val response = send(request, HttpResponse.BodyHandlers.ofInputStream(), follow)
        response.body().use { stream ->
            if (response.statusCode() != 200) return null
            val bytes = stream.readNBytes(maxBytes + 1)
            return bytes.takeIf { it.size <= maxBytes }
        }
    }

    /**
     * Like [get], following up to [maxRedirects] HTTP redirects while verifying [allowed] before the initial request and before
     * every redirect hop, so an allowlisted host cannot redirect the server to an internal or disallowed address.
     */
    fun getChecked(
        address: URI,
        timeout: Duration,
        maxBytes: Int,
        maxRedirects: Int = 3,
        allowed: (URI) -> Boolean,
    ): ByteArray? {
        var current = address
        for (hop in 0..maxRedirects) {
            if (!allowed(current)) return null
            val request = HttpRequest.newBuilder(current).timeout(timeout).GET().build()
            val response = send(request, HttpResponse.BodyHandlers.ofInputStream(), follow = false)
            val status = response.statusCode()
            if (status in REDIRECTS) {
                response.body().use { /* discard redirect body */ }
                if (hop == maxRedirects) return null
                val location = response.headers().firstValue("location").orElse(null) ?: return null
                current = runCatching { current.resolve(location) }.getOrNull() ?: return null
                continue
            }
            response.body().use { stream ->
                if (status != 200) return null
                val bytes = stream.readNBytes(maxBytes + 1)
                return bytes.takeIf { it.size <= maxBytes }
            }
        }
        return null
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
}
