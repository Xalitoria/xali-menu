package mc.xalitoria.menu.paper.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.xalitoria.menu.MenuContext

/** Unlike `legacySection()`, this keeps hex colors (as `§x§r§r§g§g§b§b`) instead of rounding them to the nearest of the 16 named ones. */
private val LEGACY = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()

/** Standard 16-color `§` serializer for Bedrock forms, which do not render Java's `§x` hex sequence. */
private val BEDROCK = LegacyComponentSerializer.legacySection()

/** Removes Unicode Private Use Area characters (`\uE000..\uF8FF`) used for Java resource-pack negative-space shifts and custom GUI glyphs. */
fun stripPrivateUse(text: String): String = buildString(text.length) {
    for (ch in text) {
        if (ch !in '\uE000'..'\uF8FF') append(ch)
    }
}.trim()

/**
 * [text] for the title of an open menu: the component the chat makes of it (`&` codes, hex, CMI gradients and MiniMessage,
 * with the colors of the menu's arguments taken out) as legacy text, which is all Paper can change on an open menu
 * (`InventoryView.setTitle`). Titles that use a font open as a component instead (see [hasFont]).
 */
fun MenuContext.legacy(text: String): String = LEGACY.serialize(component(text))

/**
 * [text] formatted for a Bedrock client: standard 16-color `§` codes (with hex rounded to the nearest named color) and,
 * when [stripPrivateUse] is true, Private Use Area glyphs removed so missing-font boxes do not appear.
 */
fun MenuContext.bedrockText(text: String, stripPrivateUse: Boolean = true): String {
    val serialized = BEDROCK.serialize(component(text))
    return if (stripPrivateUse) stripPrivateUse(serialized) else serialized
}

/** Same as [bedrockText], returned as an Adventure [Component] for [org.bukkit.Bukkit.createInventory]. */
fun MenuContext.bedrockComponent(text: String, stripPrivateUse: Boolean = true): Component =
    BEDROCK.deserialize(bedrockText(text, stripPrivateUse))

/** Whether this or any part of it is written in a font, which legacy text cannot say. */
fun Component.hasFont(): Boolean = font() != null || children().any { it.hasFont() }
