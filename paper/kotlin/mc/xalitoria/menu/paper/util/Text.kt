package mc.xalitoria.menu.paper.util

import mc.xalitoria.color.stripCmiGradients
import mc.xalitoria.color.stripColors
import mc.xalitoria.util.message.MINI

/**
 * This text as a message argument shows as it is written: the color codes, CMI gradients and MiniMessage tags it holds
 * are taken out, since the message functions would otherwise read them as formatting. For text that comes from a file
 * or a command line rather than from the messages file.
 */
fun String.verbatim(): String = MINI.escapeTags(stripCmiGradients().stripColors())
