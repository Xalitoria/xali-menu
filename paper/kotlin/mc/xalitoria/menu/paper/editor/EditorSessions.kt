package mc.xalitoria.menu.paper.editor

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The links `/xmenu edit` and `/xmenu share` (when using the direct HTTP endpoint) hand out. A link holds a random token
 * that stands for one session for a while; the editor sends it with every request and nothing else gives access. Tokens
 * are only kept as hashes, and are found by hash, so a token cannot be guessed from how long a comparison takes.
 */
internal class EditorSessions(private val minutes: () -> Int, private val now: () -> Long = System::currentTimeMillis) {
    data class Session(
        val owner: String,
        var expires: Long,
        val shared: Boolean = false,
        val readOnly: Boolean = false,
        val autoReload: Boolean = false,
        val initialMenu: String? = null,
        val ownerIp: String? = null,
        val ownerUuid: UUID? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    /** Makes a token for [owner]. A [shared] token holds saves for admin approval in [PendingEdits] instead of writing directly. */
    fun create(
        owner: String,
        shared: Boolean = false,
        readOnly: Boolean = false,
        autoReload: Boolean = false,
        initialMenu: String? = null,
        ownerIp: String? = null,
        ownerUuid: UUID? = null,
    ): String {
        purge()
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.expires }?.key ?: break
            sessions.remove(oldest)
        }
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[hash(token)] = Session(
            owner = owner,
            expires = now() + minutes() * 60_000L,
            shared = shared,
            readOnly = readOnly,
            autoReload = autoReload,
            initialMenu = initialMenu,
            ownerIp = EditorGuard.normalizeIp(ownerIp),
            ownerUuid = ownerUuid,
        )
        return token
    }

    /** The active session [token] stands for, or null if it is not one or has run out. */
    fun session(token: String?): Session? {
        if (token.isNullOrEmpty()) return null
        val key = hash(token)
        val session = sessions[key] ?: return null
        if (session.expires < now()) { sessions.remove(key); return null }
        return session
    }

    /** Whom [token] stands for, or null if it is not one or has run out. */
    fun owner(token: String?): String? = session(token)?.owner

    /** Ends shared sessions created by [owner], or all shared sessions when [owner] is null. @return how many ended */
    fun clearShared(owner: String? = null): Int = clearMatching(owner, shared = true)

    /** Ends admin edit sessions created by [owner], or all edit sessions when [owner] is null. @return how many ended */
    fun clearEdit(owner: String? = null): Int = clearMatching(owner, shared = false)

    private fun clearMatching(owner: String?, shared: Boolean): Int {
        var removed = 0
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (s.shared == shared && (owner == null || s.owner == owner)) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    /** Ends every session (the editor is turned off). */
    fun clear() = sessions.clear()

    private fun purge() {
        val time = now()
        sessions.values.removeIf { it.expires < time }
    }

    private fun hash(token: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        const val MAX_SESSIONS = 64
    }
}
