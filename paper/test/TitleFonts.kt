import com.xalitoria.util.message.toComponent
import mc.xalitoria.menu.paper.util.hasFont
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A title is opened as a component only when it holds a font, which legacy text cannot say. */
class TitleFonts {
    @Test
    fun aGlyphInAFontIsFound() {
        assertTrue("<font:nexo:shift></font><font:nexo:default>ᛧ</font>".toComponent().hasFont())
        assertTrue("&8Shop <font:nexo:default>ᛧ</font>".toComponent().hasFont())
        assertTrue("<white><font:nexo:default>ᛮᛯᛧ</font></white>".toComponent().hasFont())
    }

    @Test
    fun plainTextHasNone() {
        assertFalse("&8Friends &7(3)".toComponent().hasFont())
        assertFalse("<red>Shop</red>".toComponent().hasFont())
    }

    @Test
    fun theFontAndColorAreKept() {
        val component = "<white><font:nexo:default>ᛮᛯᛧ</font></white>".toComponent()
        fun firstFont(node: Component): String? = node.font()?.asString() ?: node.children().firstNotNullOfOrNull(::firstFont)
        fun hasWhite(node: Component): Boolean = node.color() == NamedTextColor.WHITE || node.children().any(::hasWhite)
        assertEquals("nexo:default", firstFont(component))
        assertTrue(hasWhite(component))
    }
}
