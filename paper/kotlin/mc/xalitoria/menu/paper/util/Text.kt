package mc.xalitoria.menu.paper.util

import com.xalitoria.color.stripCmiGradients
import com.xalitoria.color.stripColors
import com.xalitoria.util.message.MINI
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.xalitoria.menu.MenuContext

/**
 * This text as a message argument shows as it is written: the color codes, CMI gradients and MiniMessage tags it holds
 * are taken out, since the message functions would otherwise read them as formatting. For text that comes from a file
 * or a command line rather than from the messages file.
 */
fun String.verbatim(): String = MINI.escapeTags(stripCmiGradients().stripColors())

/** Unlike `legacySection()`, this keeps hex colors (as `§x§r§r§g§g§b§b`) instead of rounding them to the nearest of the 16 named ones. */
private val LEGACY = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()

/**
 * [text] for the title of an open menu: the component the chat makes of it (`&` codes, hex, CMI gradients and MiniMessage,
 * with the colors of the menu's arguments taken out) as legacy text, which is all Paper can change on an open menu
 * (`InventoryView.setTitle`). Fonts and events are lost, which a title does not use.
 */
fun MenuContext.legacy(text: String): String = LEGACY.serialize(component(text))
