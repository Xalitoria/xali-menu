package mc.xalitoria.menu.paper.util

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.xalitoria.menu.MenuContext

/** Unlike `legacySection()`, this keeps hex colors (as `§x§r§r§g§g§b§b`) instead of rounding them to the nearest of the 16 named ones. */
private val LEGACY = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()

/**
 * [text] for the title of an open menu: the component the chat makes of it (`&` codes, hex, CMI gradients and MiniMessage,
 * with the colors of the menu's arguments taken out) as legacy text, which is all Paper can change on an open menu
 * (`InventoryView.setTitle`). Fonts and events are lost, which a title does not use.
 */
fun MenuContext.legacy(text: String): String = LEGACY.serialize(component(text))
