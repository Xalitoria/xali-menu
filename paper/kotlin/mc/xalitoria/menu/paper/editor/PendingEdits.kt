package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The saves that came through a shared link and wait for an admin. A menu can run console commands, so what comes from a link that
 * several people may hold is never written by itself: an admin lists it (`/xmenu edits`), looks at the problems the plugin found and
 * applies or drops it.
 */
internal class PendingEdits(private val limit: Int = 50) {
    /** One save of [menu] as [name], sent by [from] (a name the sender chose, so it proves nothing). */
    data class Edit(val id: Int, val from: String, val name: String, val menu: ConfigObject, val problems: List<String>, val at: Long)

    private val counter = AtomicInteger()
    private val edits = LinkedHashMap<Int, Edit>()

    /** Holds [menu]. A later save of the same menu by the same sender replaces the earlier one; when too many wait, the oldest goes. */
    @Synchronized
    fun add(from: String, name: String, menu: ConfigObject, problems: List<String>, now: Long = System.currentTimeMillis()): Edit {
        edits.values.removeIf { it.from == from && it.name == name }
        val edit = Edit(counter.incrementAndGet(), from, name, menu, problems, now)
        edits[edit.id] = edit
        while (edits.size > limit) edits.remove(edits.keys.first())
        return edit
    }

    /** The edits that wait, oldest first. */
    @Synchronized
    fun list(): List<Edit> = edits.values.toList()

    /** Takes edit [id] out of the list, or null if there is none. */
    @Synchronized
    fun take(id: Int): Edit? = edits.remove(id)

    @Synchronized
    fun clear() = edits.clear()
}
