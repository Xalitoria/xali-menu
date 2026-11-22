package mc.xalitoria.menu.paper.hook

import com.nexomc.nexo.glyphs.GlyphTag
import com.nexomc.nexo.glyphs.ShiftTag
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player

/**
 * Nexo's `<glyph:id>` and `<shift:n>` tags in a menu's text (`title = "<white><shift:-18><glyph:friends></white>"`). Nexo reads them
 * with its own MiniMessage, so each tag is turned into what Nexo makes of it (a text in the font of Nexo's pack, as `<font:...>`
 * MiniMessage) before the text is colored like all others. Does nothing while Nexo is not loaded.
 */
object NexoTags {
    private val TAG = Regex("<(?:shift|glyph):[^>]+>")
    private val MINI = MiniMessage.miniMessage()

    /** Set by the plugin when Nexo is enabled, since this loads Nexo's classes. */
    @Volatile var enabled = false

    fun has(text: String): Boolean = enabled && '<' in text && TAG.containsMatchIn(text)

    /** [text] with each Nexo tag replaced; [player] is who the glyph is for, since Nexo may ask for a permission. */
    fun expand(text: String, player: Player): String = try {
        TAG.replace(text) { match ->
            val tag = match.value
            val resolver = if (tag.startsWith("<shift")) ShiftTag.RESOLVER else GlyphTag.RESOLVER
            MINI.serialize(MINI.deserialize(tag, player, resolver))
        }
    } catch (e: LinkageError) {
        text
    }
}
