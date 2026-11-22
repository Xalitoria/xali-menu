package mc.xalitoria.menu.paper.hook

import com.nexomc.nexo.glyphs.GlyphTag
import com.nexomc.nexo.glyphs.ShiftTag
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * Nexo's `<glyph:id>` and `<shift:n>` tags in a menu's text (`title = "<white><shift:-18><glyph:friends></white>"`). Nexo reads them
 * with its own MiniMessage, so each tag is turned into what Nexo makes of it (a text in the font of Nexo's pack, as `<font:...>`
 * MiniMessage) before the text is colored like all others. Does nothing while Nexo is not loaded.
 *
 * Resolved without a player target: menu text is written by the server, not typed by a player, so Nexo's chat-emoji
 * permissions (`nexo.shift`, `nexo.glyphs.<id>`) must not hide menu backgrounds from non-OP players.
 */
object NexoTags {
    private val TAG = Regex("<(?:shift|glyph):[^>]+>")
    private val MINI = MiniMessage.miniMessage()

    /** Set by the plugin when Nexo is enabled, since this loads Nexo's classes. */
    @Volatile var enabled = false

    fun has(text: String): Boolean = enabled && '<' in text && TAG.containsMatchIn(text)

    /** [text] with each Nexo tag replaced; a tag that fails to resolve is left as written. */
    fun expand(text: String): String = try {
        TAG.replace(text) { match ->
            val tag = match.value
            val resolver = if (tag.startsWith("<shift")) ShiftTag.RESOLVER else GlyphTag.RESOLVER
            try {
                MINI.serialize(MINI.deserialize(tag, resolver))
            } catch (e: RuntimeException) {
                tag
            }
        }
    } catch (e: LinkageError) {
        text
    }
}
