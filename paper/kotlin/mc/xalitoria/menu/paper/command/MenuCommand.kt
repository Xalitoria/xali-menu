package mc.xalitoria.menu.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.deluxemenus.BukkitMaterials
import mc.xalitoria.menu.paper.deluxemenus.DeluxeMenusImporter
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.util.suggestMatching
import mc.xalitoria.menu.paper.util.verbatim
import mc.xalitoria.util.bukkit.getPlayer
import mc.xalitoria.util.message.*
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/xmenu import deluxemenus [overwrite] [folder]` converts the menus of a DeluxeMenus folder (`plugins/DeluxeMenus`
 * unless a folder is given) into `menus/<name>.conf`. A menu that already has a file is kept as it is unless
 * `overwrite` comes before the folder. `/xmenu reload` reads the configuration and the messages again.
 */
object MenuCommand {
    private val plugin get() = XaliMenu.INSTANCE
    private val cmdMsg get() = plugin.messageConfig.data.commands
    private val msg get() = cmdMsg.importer
    private val menuMsg get() = cmdMsg.menu

    fun register(registrar: Commands) {
        registrar.register(builder().build(), "Manages XaliMenu's menus", listOf("menu", "xm"))
    }

    private fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal("xmenu")
        .executes { ctx -> ctx.source.sender.help(); Command.SINGLE_SUCCESS }
        .then(Commands
            .literal("reload")
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.allowed()) return@executes Command.SINGLE_SUCCESS
                debug("{0} reloaded the configuration", sender.name)
                plugin.reload()
                sender pass cmdMsg.reload.pass
                Command.SINGLE_SUCCESS
            }
        )
        .then(Commands
            .literal("list")
            .executes { ctx ->
                val sender = ctx.source.sender
                if (!sender.allowed()) return@executes Command.SINGLE_SUCCESS
                val names = plugin.menus.names
                if (names.isEmpty()) sender info menuMsg.listEmpty
                else sender info menuMsg.list(names.size, names.joinToString().verbatim())
                Command.SINGLE_SUCCESS
            }
        )
        .then(Commands
            .literal("open")
            .then(Commands
                .argument("menu", StringArgumentType.word())
                .suggests { _, builder -> builder.suggestMatching(plugin.menus.names) }
                .executes { ctx -> open(ctx, StringArgumentType.getString(ctx, "menu"), target = null) }
                .then(Commands
                    .argument("player", ArgumentTypes.player())
                    .executes { ctx -> open(ctx, StringArgumentType.getString(ctx, "menu"), ctx.getPlayer("player")) }
                )
            )
        )
        .then(Commands
            .literal("import")
            .then(Commands
                .literal("deluxemenus")
                .executes { import(it, folder = null, overwrite = false) }
                .then(folder(overwrite = false))
                .then(Commands
                    .literal("overwrite")
                    .executes { import(it, folder = null, overwrite = true) }
                    .then(folder(overwrite = true))
                )
            )
        )

    /** Opens [menu] for [target], or for the sender if it is a player and no target is given. */
    private fun open(ctx: CommandContext<CommandSourceStack>, menu: String, target: Player?): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        if (plugin.menus.menu(menu) == null) {
            sender fail menuMsg.unknown(menu.verbatim())
            return Command.SINGLE_SUCCESS
        }
        val player = target ?: sender as? Player
        if (player == null) {
            sender fail menuMsg.playerOnly
            return Command.SINGLE_SUCCESS
        }
        plugin.menus.open(player, menu)
        if (player !== sender) sender pass menuMsg.opened(menu.verbatim(), player.name)
        return Command.SINGLE_SUCCESS
    }

    /** The rest of the line is the folder, so a path needs no quoting. */
    private fun folder(overwrite: Boolean) = Commands
        .argument("folder", StringArgumentType.greedyString())
        .executes { import(it, StringArgumentType.getString(it, "folder"), overwrite) }

    private fun CommandSender.help() {
        if (allowed()) this send cmdMsg.help("xmenu")
    }

    /** Whether this sender may use the subcommands, telling them if not. */
    private fun CommandSender.allowed(): Boolean {
        if (hasPermission(Permissions.ADMIN)) return true
        debug("/xmenu denied for {0}: missing {1}", name, Permissions.ADMIN)
        this fail cmdMsg.noPermission
        return false
    }

    private fun import(ctx: CommandContext<CommandSourceStack>, folder: String?, overwrite: Boolean): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS

        val source = folder?.let(Path::of) ?: plugin.server.pluginsFolder.toPath().resolve("DeluxeMenus")
        if (!source.isDirectory()) {
            sender fail msg.notAFolder(source.toString().verbatim())
            return Command.SINGLE_SUCCESS
        }

        // Reading and converting the files is slow-ish disk work and touches no world, so it stays off the region threads.
        debug("{0} imports DeluxeMenus from {1} (overwrite={2})", sender.name, source, overwrite)
        plugin.scope.launch {
            try {
                import(sender, source, overwrite)
            } catch (e: Exception) {
                plugin.logger.fail("Importing from ${source.toString().verbatim()} failed", e)
                sender fail msg.failed((e.message ?: e::class.simpleName.orEmpty()).verbatim())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private suspend fun import(sender: CommandSender, source: Path, overwrite: Boolean) = withContext(Dispatchers.IO) {
        val importer = DeluxeMenusImporter(MenuFormat(plugin.registry.module), BukkitMaterials)
        val report = importer.read(source)
        val target = plugin.dataPath.resolve("menus")
        val written = importer.write(report, target, overwrite)

        report.failures.forEach { sender fail it.verbatim() }
        if (report.menus.isEmpty()) {
            sender warn msg.nothing(source.toString().verbatim())
            return@withContext
        }
        sender pass msg.imported(written.written.size, report.menus.size, target.toString().verbatim())
        if (written.written.isNotEmpty()) plugin.menus.load()
        if (written.skipped.isNotEmpty()) {
            sender warn msg.kept(written.skipped.size, written.skipped.joinToString { it.fileName.toString() }.verbatim())
        }

        val notes = report.menus.flatMap { imported -> imported.warnings.map { "${imported.name}: $it" } }
        if (notes.isNotEmpty()) {
            sender warn msg.notes(notes.size)
            notes.take(msg.notesShown).forEach { sender info it.verbatim() }
            if (notes.size > msg.notesShown) sender info msg.more(notes.size - msg.notesShown)
            plugin.logger.warn(msg.notes(notes.size) + "\n" + notes.joinToString("\n") { "- ${it.verbatim()}" })
        }
    }
}
