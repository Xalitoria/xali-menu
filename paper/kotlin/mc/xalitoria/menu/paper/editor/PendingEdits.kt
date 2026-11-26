package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The saves that came through a shared link or failed IP verification and wait for an admin. A menu can run console commands,
 * so untrusted saves are never written by themselves: an admin lists them (`/xmenu edits`), inspects any problems or privileged
 * actions (`/xmenu edits inspect <id>`), and applies or drops them.
 */
internal class PendingEdits(private val limit: Int = 50, private val perSourceLimit: Int = 10) {
    /** One save of [menu] as [name], sent by [from] (on shared links, a self-reported name). */
    data class Edit(
        val id: Int,
        val from: String,
        val name: String,
        val menu: ConfigObject,
        val problems: List<String>,
        val at: Long,
        val clientIp: String? = null,
        val reason: String = "shared link",
        val privileged: List<String> = emptyList(),
        val privilegedTotal: Int = privileged.size,
        val privilegedTruncated: Boolean = privilegedTotal > privileged.size,
    )

    private val counter = AtomicInteger()
    private val edits = LinkedHashMap<Int, Edit>()

    /**
     * Holds [menu]. A later save of the same menu by the same sender replaces the earlier one. A single client IP is capped
     * at [perSourceLimit] waiting edits so rotating sender names cannot evict other admins' pending edits; when overall [limit]
     * is exceeded, the oldest goes.
     */
    @Synchronized
    fun add(
        from: String,
        name: String,
        menu: ConfigObject,
        problems: List<String>,
        now: Long = System.currentTimeMillis(),
        clientIp: String? = null,
        reason: String = "shared link",
        privileged: List<String> = emptyList(),
        privilegedTotal: Int = privileged.size,
        privilegedTruncated: Boolean = privilegedTotal > privileged.size,
    ): Edit {
        edits.values.removeIf { it.from == from && it.name == name && it.clientIp == clientIp }
        if (clientIp != null && perSourceLimit > 0) {
            val sameIp = edits.entries.filter { it.value.clientIp == clientIp }.map { it.key }
            val dropCount = sameIp.size - perSourceLimit + 1
            for (i in 0 until dropCount.coerceAtLeast(0)) edits.remove(sameIp[i])
        }
        val edit = Edit(counter.incrementAndGet(), from, name, menu, problems, now, clientIp, reason, privileged, privilegedTotal, privilegedTruncated)
        edits[edit.id] = edit
        while (edits.size > limit) edits.remove(edits.keys.first())
        return edit
    }

    /** The edits that wait, oldest first. */
    @Synchronized
    fun list(): List<Edit> = edits.values.toList()

    /** Looks up edit [id] without removing it, or null if there is none. */
    @Synchronized
    fun get(id: Int): Edit? = edits[id]

    /** Takes edit [id] out of the list, or null if there is none. */
    @Synchronized
    fun take(id: Int): Edit? = edits.remove(id)

    @Synchronized
    fun clear() = edits.clear()
}
