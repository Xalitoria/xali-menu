package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import mc.xalitoria.menu.paper.config.MainConfig.VerifyIp
import java.net.InetAddress

/**
 * Pure security checks for the web editor: IP normalization/matching, save authorization policy, and scanning pending edits
 * for privileged actions (`console`, `permission`, `group`, `meta`, `gamemode`). Kept server-free so every rule has unit tests.
 */
internal object EditorGuard {
    /** Action types that can escalate privileges or execute arbitrary commands on the server. */
    private val PRIVILEGED_ACTIONS = setOf("console", "permission", "group", "meta", "gamemode")
    private val NUMERIC_IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val NUMERIC_IPV6 = Regex("[0-9a-fA-F:]+(?:\\.\\d{1,3}){3}|[0-9a-fA-F:]+")
    private const val MAX_WALK_DEPTH = 32

    /** Outcome of checking whether a save request may write straight to disk or must be held / refused. */
    sealed interface SaveDecision {
        data object Allow : SaveDecision
        data object ReadOnly : SaveDecision
        data object PermissionRevoked : SaveDecision
        data class Hold(val reason: String, val code: String) : SaveDecision
    }

    /** Parses [raw] only when it is a numeric IPv4/IPv6 literal (never triggers DNS resolution). */
    fun parseIp(raw: String?): InetAddress? {
        val cleaned = raw?.trim()
            ?.removePrefix("[")?.removeSuffix("]")
            ?.substringBefore('%')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (!NUMERIC_IPV4.matches(cleaned) && !(cleaned.contains(':') && NUMERIC_IPV6.matches(cleaned))) return null
        return runCatching { InetAddress.getByName(cleaned) }.getOrNull()
    }

    /** Canonical string form of a numeric IP literal (e.g. `::ffff:1.2.3.4` -> `1.2.3.4`), or null if invalid. */
    fun normalizeIp(raw: String?): String? = parseIp(raw)?.hostAddress

    /** Whether [raw] is a loopback address (`127.0.0.1`, `::1`, `::ffff:127.0.0.1`, etc.). */
    fun isLoopback(raw: String?): Boolean = parseIp(raw)?.isLoopbackAddress == true

    /** Whether [a] and [b] refer to the same host (including IPv4-mapped IPv6 and loopback equivalence `127.0.0.1` == `::1`). */
    fun ipsMatch(a: String?, b: String?): Boolean {
        val left = parseIp(a) ?: return false
        val right = parseIp(b) ?: return false
        if (left.isLoopbackAddress && right.isLoopbackAddress) return true
        return left == right
    }

    /**
     * Determines the caller's IP for a direct HTTP request. Forwarding headers (`CF-Connecting-IP`, `X-Forwarded-For`) are
     * honored only when [trustProxy] is enabled AND the direct socket [remoteIp] is loopback.
     */
    fun resolveClientIp(remoteIp: String?, cfConnectingIp: String?, forwardedFor: String?, trustProxy: Boolean): String? {
        if (trustProxy && isLoopback(remoteIp)) {
            normalizeIp(cfConnectingIp)?.let { return it }
            forwardedFor?.split(',')?.firstOrNull()?.let(::normalizeIp)?.let { return it }
        }
        return normalizeIp(remoteIp)
    }

    /**
     * Decides whether a save request may write directly to disk, must be held in [PendingEdits], or must be refused.
     *
     * - `readOnly`: always refused (403).
     * - `shared`: always held in [PendingEdits].
     * - `ownerStillAllowed == false`: refused (403) when the player who created the link has lost `xalimenu.admin`.
     * - [VerifyIp.OFF]: allowed directly.
     * - [VerifyIp.OWNER]: allowed only when [clientIp] matches [ownerIp] (console sessions hold for approval).
     * - [VerifyIp.ADMIN]: allowed when [clientIp] matches [ownerIp] OR any IP in [onlineAdminIps] (supports console
     *   `/xmenu edit` and sharing edit links with other online admins, while holding unrecognized IPs).
     */
    fun checkSave(
        policy: VerifyIp,
        shared: Boolean,
        readOnly: Boolean,
        ownerIp: String?,
        ownerStillAllowed: Boolean,
        clientIp: String?,
        onlineAdminIps: Collection<String>,
    ): SaveDecision {
        if (readOnly) return SaveDecision.ReadOnly
        if (shared) return SaveDecision.Hold("shared link", "shared")
        if (!ownerStillAllowed) return SaveDecision.PermissionRevoked
        return when (policy) {
            VerifyIp.OFF -> SaveDecision.Allow
            VerifyIp.OWNER -> when {
                ownerIp == null -> SaveDecision.Hold("console session (no player IP)", "ip-mismatch")
                clientIp == null -> SaveDecision.Hold("client IP unavailable", "ip-mismatch")
                ipsMatch(ownerIp, clientIp) -> SaveDecision.Allow
                else -> SaveDecision.Hold("IP mismatch ($clientIp)", "ip-mismatch")
            }
            VerifyIp.ADMIN -> when {
                clientIp == null -> SaveDecision.Hold("client IP unavailable", "ip-mismatch")
                ownerIp != null && ipsMatch(ownerIp, clientIp) -> SaveDecision.Allow
                onlineAdminIps.any { ipsMatch(it, clientIp) } -> SaveDecision.Allow
                else -> SaveDecision.Hold("IP mismatch ($clientIp)", "ip-mismatch")
            }
        }
    }

    /**
     * Walks [menu] and summarizes every privileged action (`console`, `permission`, `group`, `meta`, `gamemode`) so admins
     * see them before applying a pending edit. Ignores `condition` / `conditions` subtrees so permission/group/gamemode
     * conditions are never flagged as actions.
     */
    fun privilegedActions(menu: ConfigObject, limit: Int = 20): List<String> {
        val found = ArrayList<String>()
        walk(menu, inCondition = false, depth = 0, out = found, limit = limit)
        return found
    }

    private fun walk(node: Any?, inCondition: Boolean, depth: Int, out: MutableList<String>, limit: Int) {
        if (depth > MAX_WALK_DEPTH || out.size >= limit || node == null) return
        when (node) {
            is ConfigObject -> {
                if (!inCondition) summarizeAction(node)?.let { out.add(it) }
                if (out.size >= limit) return
                for ((key, value) in node) {
                    val childCondition = when (key.lowercase()) {
                        "condition", "conditions" -> true
                        "deny", "actions", "action", "then", "reject", "on-open", "on-close", "on-start", "on-end", "on-submit", "on-cancel" -> false
                        else -> inCondition
                    }
                    walk(value, childCondition, depth + 1, out, limit)
                    if (out.size >= limit) return
                }
            }
            is ConfigList -> {
                for (item in node) {
                    walk(item, inCondition, depth + 1, out, limit)
                    if (out.size >= limit) return
                }
            }
        }
    }

    private fun summarizeAction(obj: ConfigObject): String? {
        val type = stringOf(obj, "type")?.lowercase() ?: return null
        if (type !in PRIVILEGED_ACTIONS) return null
        return when (type) {
            "console" -> "console: ${cleanValue(stringOf(obj, "command") ?: "")}"
            "permission" -> {
                val prefix = if (boolOf(obj, "take")) "-" else "+"
                "permission: $prefix${cleanValue(stringOf(obj, "node") ?: "")}"
            }
            "group" -> {
                val prefix = if (boolOf(obj, "take")) "-" else "+"
                "group: $prefix${cleanValue(stringOf(obj, "name") ?: "")}"
            }
            "meta" -> {
                val key = cleanValue(stringOf(obj, "key") ?: "")
                val value = stringOf(obj, "value")?.let { "=${cleanValue(it)}" } ?: " (unset)"
                "meta: $key$value"
            }
            "gamemode" -> {
                // Gamemode conditions use `modes` (a list), whereas the action uses `mode`.
                val mode = stringOf(obj, "mode") ?: return null
                "gamemode: ${cleanValue(mode)}"
            }
            else -> null
        }
    }

    private fun stringOf(obj: ConfigObject, key: String): String? = obj[key]?.unwrapped()?.toString()

    private fun boolOf(obj: ConfigObject, key: String): Boolean {
        val raw = obj[key]?.unwrapped() ?: return false
        return when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun cleanValue(text: String): String =
        text.filter { !it.isISOControl() && it != '§' }.trim().take(64)
}
