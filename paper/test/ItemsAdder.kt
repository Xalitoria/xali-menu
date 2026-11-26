import mc.xalitoria.menu.paper.hook.ItemsAdderTags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItemsAdderTest {
    @Test
    fun expandsOffsetsGlyphTagsPlaceholdersAndInlineColonFontImages() {
        val offsets = mapOf(-16 to "\uF801", 8 to "\uF802")
        val glyphs = mapOf("my:gui" to "\uE001", "my:icon" to "\uE002")
        val expanded = ItemsAdderTags.expand(
            text = "<shift:-16><glyph:my:gui> :offset_8:%img_my:icon% :my:icon: <image:my:gui> %img_offset_-16%",
            resolveOffset = offsets::get,
            resolveGlyph = glyphs::get,
            replaceInline = { it.replace(":my:icon:", "\uE002") },
        )
        assertEquals("\uF801\uE001 \uF802\uE002 \uE002 \uE001 \uF801", expanded)
    }

    @Test
    fun leavesUnknownTagsAndPlainColonsUntouched() {
        val expanded = ItemsAdderTags.expand(
            text = "Time: 12:30 <glyph:unknown:thing> %img_missing% :unknown:id:",
            resolveOffset = { null },
            resolveGlyph = { null },
            replaceInline = { it },
        )
        assertEquals("Time: 12:30 <glyph:unknown:thing> %img_missing% :unknown:id:", expanded)
    }

    @Test
    fun hasRespectsEnabledFlagAndDetectsSupportedSyntaxes() {
        val previous = ItemsAdderTags.enabled
        try {
            ItemsAdderTags.enabled = false
            assertFalse(ItemsAdderTags.has(":my:gui:"))
            assertFalse(ItemsAdderTags.has("%img_offset_-16%"))

            ItemsAdderTags.enabled = true
            assertTrue(ItemsAdderTags.has(":my:gui:"))
            assertTrue(ItemsAdderTags.has(":offset_-16:"))
            assertTrue(ItemsAdderTags.has("%img_offset_4%"))
            assertTrue(ItemsAdderTags.has("%img_my:icon%"))
            assertTrue(ItemsAdderTags.has("<glyph:my:gui>"))
            assertTrue(ItemsAdderTags.has("<shift:-18>"))
            assertFalse(ItemsAdderTags.has("Plain title with no tags"))
        } finally {
            ItemsAdderTags.enabled = previous
        }
    }
}
