package mc.xalitoria.menu.paper.editor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks active web-editor collaborators so every open browser tab sees Google Docs-style presence badges and slot/item
 * highlights for both in-game players (matched by IP) and anonymous/browser collaborators. Pure and server-free for tests.
 */
internal class EditorPresence(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Incoming state from one browser tab. */
    data class Beat(
        val clientId: String,
        val name: String?,
        val inGame: Boolean,
        val admin: Boolean,
        val menu: String?,
        val item: String?,
        val slot: Int?,
        val area: String?,
        val tab: String?,
    )

    private data class Entry(
        val id: String,
        val name: String,
        val inGame: Boolean,
        val admin: Boolean,
        val color: String,
        val menu: String?,
        val item: String?,
        val slot: Int?,
        val area: String?,
        val tab: String?,
        val seenAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val anonNames = ConcurrentHashMap<String, String>()
    private val anonCounter = AtomicInteger()

    /** Records a heartbeat for [beat] and returns every currently active collaborator (including self, marked with [PeerInfo.self]). */
    @Synchronized
    fun update(beat: Beat): List<PeerInfo> {
        val id = cleanId(beat.clientId) ?: return emptyList()
        val time = now()
        prune(time)
        val existing = entries[id]
        val color = existing?.color ?: pickColor()
        val displayName = resolveName(id, beat.name, beat.inGame)
        if (existing == null && entries.size >= MAX_PEERS) {
            val oldest = entries.entries.minByOrNull { it.value.seenAt }?.key
            if (oldest != null) {
                entries.remove(oldest)
                anonNames.remove(oldest)
            }
        }
        entries[id] = Entry(
            id = id,
            name = displayName,
            inGame = beat.inGame,
            admin = beat.admin,
            color = color,
            menu = beat.menu?.take(64),
            item = beat.item?.take(64),
            slot = beat.slot?.takeIf { it in 0..99 },
            area = beat.area?.takeIf { it == "items" || it == "inventory" },
            tab = beat.tab?.take(16),
            seenAt = time,
        )
        return snapshot(selfId = id)
    }

    /** Removes a collaborator immediately when their tab closes or disconnects. */
    @Synchronized
    fun remove(clientId: String?) {
        val id = cleanId(clientId) ?: return
        entries.remove(id)
        anonNames.remove(id)
    }

    /** Current active collaborator count after expiring stale entries. */
    @Synchronized
    fun activeCount(): Int {
        prune(now())
        return entries.size
    }

    @Synchronized
    fun clear() {
        entries.clear()
        anonNames.clear()
    }

    private fun prune(time: Long) {
        val expired = entries.entries.filter { time - it.value.seenAt > ttlMs }.map { it.key }
        for (key in expired) {
            entries.remove(key)
            anonNames.remove(key)
        }
    }

    private fun pickColor(): String {
        val used = entries.values.map { it.color }.toSet()
        return PALETTE.firstOrNull { it !in used } ?: PALETTE[entries.size % PALETTE.size]
    }

    private fun resolveName(id: String, raw: String?, inGame: Boolean): String {
        val cleaned = raw?.filter { !it.isISOControl() && it != '§' && it != '&' }?.trim()?.take(32).orEmpty()
        if (inGame && cleaned.isNotEmpty()) return cleaned
        if (cleaned.isNotEmpty() && !cleaned.equals("guest", ignoreCase = true)) return cleaned
        return anonNames.computeIfAbsent(id) { "Anonymous #${anonCounter.incrementAndGet()}" }
    }

    private fun snapshot(selfId: String): List<PeerInfo> = entries.values
        .sortedWith(compareByDescending<Entry> { it.id == selfId }.thenByDescending { it.inGame }.thenBy { it.name.lowercase() })
        .map {
            PeerInfo(
                id = it.id,
                name = it.name,
                inGame = it.inGame,
                admin = it.admin,
                color = it.color,
                menu = it.menu,
                item = it.item,
                slot = it.slot,
                area = it.area,
                tab = it.tab,
                self = it.id == selfId,
            )
        }

    companion object {
        const val DEFAULT_TTL_MS = 15_000L
        const val MAX_PEERS = 64
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{4,48}")
        val PALETTE = [
            "#3b82f6",
            "#ec4899",
            "#10b981",
            "#f59e0b",
            "#8b5cf6",
            "#ef4444",
            "#06b6d4",
            "#f97316",
        ]

        fun cleanId(raw: String?): String? = raw?.trim()?.takeIf { ID_PATTERN.matches(it) }
    }
}
