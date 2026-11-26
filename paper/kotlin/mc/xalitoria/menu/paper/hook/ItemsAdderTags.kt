package mc.xalitoria.menu.paper.hook

import dev.lone.itemsadder.api.FontImages.FontImageWrapper

/**
 * ItemsAdder font images and pixel offsets in menu text (`:namespace:id:`, `:offset_-16:`, `%img_offset_-16%`,
 * `%img_namespace:id%`, `<glyph:namespace:id>`, `<image:namespace:id>`, `<shift:-16>`, `<s:-16>`).
 *
 * Uses the no-player overload of [FontImageWrapper.replaceFontImages] so server-authored menu titles and item text
 * do not require player chat-emoji permissions. Does nothing while ItemsAdder is not enabled.
 */
object ItemsAdderTags {
    private val SHIFT_TAG = Regex("<(?:shift|s):(-?\\d+)>", RegexOption.IGNORE_CASE)
    private val IMG_OFFSET = Regex("%img_offset_(-?\\d+)%", RegexOption.IGNORE_CASE)
    private val COLON_OFFSET = Regex(":offset_(-?\\d+):", RegexOption.IGNORE_CASE)
    private val GLYPH_TAG = Regex("<(?:glyph|g|image):([^<>\\s]+)>", RegexOption.IGNORE_CASE)
    private val IMG_PLACEHOLDER = Regex("%img_([^%\\s]+)%", RegexOption.IGNORE_CASE)
    private val COLON_GLYPH = Regex(":[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?:")

    /** Set by the plugin when ItemsAdder is enabled, since calling ItemsAdder loads its classes. */
    @Volatile
    var enabled = false

    fun has(text: String): Boolean {
        if (!enabled) return false
        return (':' in text && (COLON_OFFSET.containsMatchIn(text) || COLON_GLYPH.containsMatchIn(text)))
            || ('<' in text && (SHIFT_TAG.containsMatchIn(text) || GLYPH_TAG.containsMatchIn(text)))
            || ('%' in text && (IMG_OFFSET.containsMatchIn(text) || IMG_PLACEHOLDER.containsMatchIn(text)))
    }

    /** [text] with each ItemsAdder font image and offset replaced; unknown tags are left as written. */
    fun expand(text: String): String = try {
        expand(
            text = text,
            resolveOffset = ::offsetString,
            resolveGlyph = ::glyphString,
            replaceInline = { input -> FontImageWrapper.replaceFontImages(input) },
        )
    } catch (_: LinkageError) {
        text
    } catch (_: RuntimeException) {
        text
    }

    /** Pure expansion seam for unit tests without a running ItemsAdder server. */
    internal fun expand(
        text: String,
        resolveOffset: (Int) -> String?,
        resolveGlyph: (String) -> String?,
        replaceInline: (String) -> String,
    ): String {
        var result = text
        val replaceShift: (MatchResult) -> CharSequence = { match ->
            val pixels = match.groupValues[1].toIntOrNull()
            if (pixels != null) resolveOffset(pixels) ?: match.value else match.value
        }
        if ('<' in result) result = SHIFT_TAG.replace(result, replaceShift)
        if ('%' in result) result = IMG_OFFSET.replace(result, replaceShift)
        if (':' in result) result = COLON_OFFSET.replace(result, replaceShift)
        if ('<' in result) {
            result = GLYPH_TAG.replace(result) { match ->
                val raw = match.groupValues[1].trim()
                resolveGlyph(raw) ?: match.value
            }
        }
        if ('%' in result) {
            result = IMG_PLACEHOLDER.replace(result) { match ->
                val raw = match.groupValues[1].trim()
                resolveGlyph(raw) ?: match.value
            }
        }
        if (':' in result && COLON_GLYPH.containsMatchIn(result)) {
            result = replaceInline(result)
        }
        return result
    }

    private fun offsetString(pixels: Int): String? = runCatching {
        FontImageWrapper.applyPixelsOffsetToString("", pixels)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun glyphString(id: String): String? = runCatching {
        val wrapper = FontImageWrapper(id)
        if (wrapper.exists()) wrapper.string?.takeIf { it.isNotEmpty() } else null
    }.getOrNull()
}
