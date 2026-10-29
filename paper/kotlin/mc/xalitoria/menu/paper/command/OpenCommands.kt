package mc.xalitoria.menu.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.util.message.*
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.entity.Player

/**
 * The commands that open menus: each one-word entry of a menu's `commands`, unless the menu sets `register = false`.
 * The rest are caught as the player types them (see [mc.xalitoria.menu.runtime.MenuListener]). The words after the
 * label are the menu's `args`.
 */
object OpenCommands {
    private val plugin get() = XaliMenu.INSTANCE

    /** The labels registered, which Paper can only change when the server starts. */
    var registered: Set<String> = emptySet()
        private set

    fun register(registrar: Commands) {
        val labels = plugin.menus.registered()
        claim(labels.keys)
        for ((key, value) in labels) {
            val taken = registrar.register(node(key, value), "Opens the menu $value", emptyList())
            if (key !in taken) plugin.logger warn "/$key could not be registered for the menu '$value'; it is still /xalimenu:$key."
        }
        registered = labels.keys
    }

    private fun node(label: String, menu: String) = Commands
        .literal(label)
        .executes { ctx -> open(ctx.source, menu, "") }
        .then(Commands
            .argument("args", StringArgumentType.greedyString())
            .executes { ctx -> open(ctx.source, menu, StringArgumentType.getString(ctx, "args")) }
        )
        .build()

    private fun open(source: CommandSourceStack, menu: String, args: String): Int {
        val player = source.sender as? Player
        if (player == null) {
            source.sender fail plugin.messageConfig.data.commands.menu.playerOnly
            return Command.SINGLE_SUCCESS
        }
        // Looked up again by name, so a menu reloaded since the command was registered is the one that opens.
        plugin.menus.open(player, menu, args.split(' ').filter { it.isNotEmpty() })
        return Command.SINGLE_SUCCESS
    }

    /**
     * Frees [labels] that another plugin already holds. Paper never replaces a plugin's command with another plugin's,
     * and a menu for `/shop` is meant to win over any other `/shop`.
     */
    private fun claim(labels: Collection<String>) {
        val known = plugin.server.commandMap.knownCommands
        val taken = labels.filter { label ->
            val holder = known.remove(label) ?: return@filter false
            (holder as? PluginIdentifiableCommand)?.plugin.let { it != null && it != plugin }
        }
        if (taken.isNotEmpty()) plugin.logger info "/${taken.joinToString(", /")} now open XaliMenu's menus instead of another plugin's command."
    }
}
