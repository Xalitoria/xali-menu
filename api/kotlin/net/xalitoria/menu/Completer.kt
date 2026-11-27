package net.xalitoria.menu

import org.bukkit.command.CommandSender

/**
 * Supplies tab-completion words for a menu command's `complete` map, registered with [XaliMenuApi.registerCompleter]
 * or [ExtensionContext.registerCompleter] and referenced in a menu file as `@plugin:name`.
 */
fun interface Completer {
    /**
     * The words to offer [sender]. Called on the thread that handles tab completion, so it must be cheap and must not
     * change anything.
     */
    fun suggest(sender: CommandSender): List<String>
}
