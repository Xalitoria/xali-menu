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
    private val PRIVILEGED_ACTIONS = setOf("console", "permission", "group", "meta", "gamemode", "player", "chat")
    private val ACTION_CONTEXT_KEYS = setOf(
        "pass", "deny", "actions", "action", "then", "reject",
        "on-open", "on-close", "on-start", "on-end", "on-submit", "on-cancel",
        "items", "click", "accept", "bedrock", "animation", "frames", "pages",
    )
    private val ITEM_MAP_KEYS = setOf("items", "map", "templates")
    private val NUMERIC_IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val NUMERIC_IPV6 = Regex("[0-9a-fA-F:]+(?:\\.\\d{1,3}){3}|[0-9a-fA-F:]+")
    internal const val MAX_WALK_DEPTH = 64
    internal const val MAX_WALK_NODES = 20_000

    /** Outcome of scanning a menu tree for privileged actions. */
    data class PrivilegedScan(
        val actions: List<String>,
        val totalFound: Int = actions.size,
        val truncated: Boolean = false,
        val depthExceeded: Boolean = false,
        val nodeLimitExceeded: Boolean = false,
    ) {
        /** False when the walk hit its depth or node ceiling and could not inspect the whole menu. */
        val scannedAll: Boolean get() = !depthExceeded && !nodeLimitExceeded

        /** True only when the whole menu was scanned and every privileged action is listed in [actions]. */
        val complete: Boolean get() = scannedAll && !truncated
    }

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
        onlineNonAdminIps: Collection<String> = emptyList(),
        ipSharedWithOtherPlayer: Boolean = false,
    ): SaveDecision {
        if (readOnly) return SaveDecision.ReadOnly
        if (shared) return SaveDecision.Hold("shared link", "shared")
        if (!ownerStillAllowed) return SaveDecision.PermissionRevoked
        val sharedWithNonAdmin = clientIp != null && onlineNonAdminIps.any { ipsMatch(it, clientIp) }
        return when (policy) {
            VerifyIp.OFF -> SaveDecision.Allow
            VerifyIp.OWNER -> when {
                ownerIp == null -> SaveDecision.Hold("console session (no player IP)", "ip-mismatch")
                clientIp == null -> SaveDecision.Hold("client IP unavailable", "ip-mismatch")
                sharedWithNonAdmin || ipSharedWithOtherPlayer -> SaveDecision.Hold("shared IP ($clientIp)", "shared-ip")
                ipsMatch(ownerIp, clientIp) -> SaveDecision.Allow
                else -> SaveDecision.Hold("IP mismatch ($clientIp)", "ip-mismatch")
            }
            VerifyIp.ADMIN -> when {
                clientIp == null -> SaveDecision.Hold("client IP unavailable", "ip-mismatch")
                sharedWithNonAdmin -> SaveDecision.Hold("shared IP ($clientIp)", "shared-ip")
                ownerIp != null && ipsMatch(ownerIp, clientIp) -> SaveDecision.Allow
                onlineAdminIps.any { ipsMatch(it, clientIp) } -> SaveDecision.Allow
                else -> SaveDecision.Hold("IP mismatch ($clientIp)", "ip-mismatch")
            }
        }
    }

    /**
     * Walks [menu] and summarizes every privileged action (`console`, `permission`, `group`, `meta`, `gamemode`,
     * `player`, command-like `chat`) so admins see them before applying a pending edit. Ignores actual `condition` /
     * `conditions` subtrees without letting item names or `pass` lists hide actions.
     */
    fun privilegedActions(menu: ConfigObject, limit: Int = 20): List<String> =
        scanPrivilegedActions(menu, limit).actions

    /** Full scan of [menu] for privileged actions, reporting truncation or depth/node-limit overflow explicitly. */
    fun scanPrivilegedActions(
        menu: ConfigObject,
        limit: Int = 20,
        maxDepth: Int = MAX_WALK_DEPTH,
        maxNodes: Int = MAX_WALK_NODES,
    ): PrivilegedScan {
        val found = ArrayList<String>()
        var total = 0
        var nodes = 0
        var depthExceeded = false
        var nodeLimitExceeded = false

        fun walk(node: Any?, inCondition: Boolean, inItemMap: Boolean, depth: Int) {
            if (node == null) return
            if (depth > maxDepth) {
                depthExceeded = true
                return
            }
            if (++nodes > maxNodes) {
                nodeLimitExceeded = true
                return
            }
            when (node) {
                is ConfigObject -> {
                    if (!inCondition) {
                        summarizeAction(node)?.let { summary ->
                            total++
                            if (found.size < limit) found.add(summary)
                        }
                    }
                    for ((key, value) in node) {
                        val lower = key.lowercase()
                        val childCondition = when {
                            inItemMap -> false
                            lower == "condition" || lower == "conditions" -> true
                            lower in ACTION_CONTEXT_KEYS -> false
                            else -> inCondition
                        }
                        val childItemMap = !childCondition && lower in ITEM_MAP_KEYS
                        walk(value, childCondition, childItemMap, depth + 1)
                        if (nodeLimitExceeded) return
                    }
                }
                is ConfigList -> {
                    for (item in node) {
                        walk(item, inCondition, inItemMap = false, depth = depth + 1)
                        if (nodeLimitExceeded) return
                    }
                }
            }
        }

        walk(menu, inCondition = false, inItemMap = false, depth = 0)
        return PrivilegedScan(
            actions = found,
            totalFound = total,
            truncated = total > found.size,
            depthExceeded = depthExceeded,
            nodeLimitExceeded = nodeLimitExceeded,
        )
    }

    private fun summarizeAction(obj: ConfigObject): String? {
        val type = stringOf(obj, "type")?.trim()?.lowercase() ?: return null
        if (type !in PRIVILEGED_ACTIONS) return null
        return when (type) {
            "console" -> "console: ${cleanValue(stringOf(obj, "command") ?: "")}"
            "player" -> "player: ${cleanValue(stringOf(obj, "command") ?: "")}"
            "chat" -> {
                val text = cleanValue(stringOf(obj, "text") ?: "")
                if (text.startsWith("/") || '{' in text || '%' in text) "chat: $text" else null
            }
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
