package mc.xalitoria.menu.paper.editor

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The links `/xmenu edit` hands out. A link holds a random token that stands for one person for a while; the editor sends it
 * with every request and nothing else gives access. Tokens are only kept as hashes, and are found by hash, so a token cannot be
 * guessed from how long a comparison takes.
 */
internal class EditorSessions(private val minutes: () -> Int, private val now: () -> Long = System::currentTimeMillis) {
    private class Session(val owner: String, var expires: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    /** Makes a token for [owner]. */
    fun create(owner: String): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[hash(token)] = Session(owner, now() + minutes() * 60_000L)
        purge()
        return token
    }

    /** Whom [token] stands for, or null if it is not one or has run out. */
    fun owner(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val session = sessions[hash(token)] ?: return null
        if (session.expires < now()) { sessions.remove(hash(token)); return null }
        return session.owner
    }

    /** Ends every session (the editor is turned off). */
    fun clear() = sessions.clear()

    private fun purge() {
        val time = now()
        sessions.values.removeIf { it.expires < time }
    }

    private fun hash(token: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
