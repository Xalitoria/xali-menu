package mc.xalitoria.menu.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.typesafe.config.ConfigRenderOptions
import com.xalitoria.util.bukkit.getPlayer
import com.xalitoria.util.bukkit.suggestMatching
import com.xalitoria.util.message.*
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.deluxemenus.BukkitMaterials
import mc.xalitoria.menu.paper.deluxemenus.DeluxeMenusImporter
import mc.xalitoria.menu.paper.editor.EditorLink
import mc.xalitoria.menu.paper.editor.EditorMenus
import mc.xalitoria.menu.paper.editor.EditorRelay
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.runtime.Clients
import mc.xalitoria.menu.paper.runtime.MenuManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.nio.file.Path
import kotlin.io.path.isDirectory

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
    private val toolMsg get() = cmdMsg.tools

    fun register(registrar: Commands) {
        registrar.register(builder().build(), "Manages XaliMenu's menus", listOf("xmenu", "menu"))
    }

    private fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal("xalimenu")
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
            .then(menuArgument().executes { ctx -> reloadOne(ctx, StringArgumentType.getString(ctx, "menu")) })
        )
        .then(Commands
            .literal("info")
            .then(menuArgument().executes { ctx -> info(ctx, StringArgumentType.getString(ctx, "menu")) })
        )
        .then(Commands
            .literal("validate")
            .executes { ctx -> validate(ctx, null) }
            .then(menuArgument().executes { ctx -> validate(ctx, StringArgumentType.getString(ctx, "menu")) })
        )
        .then(Commands
            .literal("inspect")
            .executes { ctx -> inspect(ctx, ctx.source.sender as? Player) }
            .then(Commands
                .argument("player", ArgumentTypes.player())
                .executes { ctx -> inspect(ctx, ctx.getPlayer("player")) }
            )
        )
        .then(Commands
            .literal("client")
            .executes { ctx -> client(ctx, ctx.source.sender as? Player) }
            .then(Commands
                .argument("player", ArgumentTypes.player())
                .executes { ctx -> client(ctx, ctx.getPlayer("player")) }
            )
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
                .executes { ctx -> open(ctx, StringArgumentType.getString(ctx, "menu"), target = null, args = emptyList()) }
                .then(Commands
                    .argument("player", ArgumentTypes.player())
                    .executes { ctx -> open(ctx, StringArgumentType.getString(ctx, "menu"), ctx.getPlayer("player"), emptyList()) }
                    .then(Commands
                        .argument("args", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val raw = StringArgumentType.getString(ctx, "args").trim().split(' ').filter { it.isNotEmpty() }
                            open(ctx, StringArgumentType.getString(ctx, "menu"), ctx.getPlayer("player"), raw)
                        }
                    )
                )
            )
        )
        .then(Commands
            .literal("edit")
            .executes { ctx -> edit(ctx, null) }
            .then(menuArgument().executes { ctx -> edit(ctx, StringArgumentType.getString(ctx, "menu")) })
        )
        .then(Commands
            .literal("share")
            .executes { ctx -> share(ctx, null, false) }
            .then(Commands.literal("stop").executes { ctx -> stopSharing(ctx) })
            .then(Commands.literal("readonly")
                .executes { ctx -> share(ctx, null, true) }
                .then(menuArgument().executes { ctx -> share(ctx, StringArgumentType.getString(ctx, "menu"), true) })
            )
            .then(menuArgument().executes { ctx -> share(ctx, StringArgumentType.getString(ctx, "menu"), false) })
        )
        .then(Commands
            .literal("edits")
            .executes { ctx -> edits(ctx) }
            .then(Commands.literal("apply").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes { ctx -> applyEdit(ctx, IntegerArgumentType.getInteger(ctx, "id")) }))
            .then(Commands.literal("drop").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes { ctx -> dropEdit(ctx, IntegerArgumentType.getInteger(ctx, "id")) }))
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

    private fun menuArgument() = Commands
        .argument("menu", StringArgumentType.word())
        .suggests { _, builder -> builder.suggestMatching(plugin.menus.names) }

    private fun reloadOne(ctx: CommandContext<CommandSourceStack>, name: String): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        when (val result = plugin.menus.reload(name)) {
            MenuManager.Reread.NoFile -> sender fail toolMsg.noFile(name.verbatim())
            is MenuManager.Reread.Failed -> sender fail toolMsg.unreadable(name.verbatim(), result.reason.verbatim())
            is MenuManager.Reread.Done -> {
                sender pass toolMsg.reloadedOne(name.verbatim())
                sender.problems(name, result.problems)
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun CommandSender.problems(name: String, problems: List<String>) {
        if (problems.isEmpty()) return
        this warn toolMsg.problems(name.verbatim(), problems.size)
        problems.forEach { this info it.verbatim() }
    }

    private fun info(ctx: CommandContext<CommandSourceStack>, name: String): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val entry = plugin.menus.menu(name)
        if (entry == null) {
            sender fail menuMsg.unknown(name.verbatim())
            return Command.SINGLE_SUCCESS
        }
        val menu = entry.menu
        val frames = menu.animation?.frames?.size
        val kind = listOfNotNull("paged".takeIf { menu.pages != null }, "animated".takeIf { frames != null }, "bedrock".takeIf { menu.bedrock != null }).ifEmpty { listOf("simple") }.joinToString(", ")
        sender info toolMsg.infoHeader(entry.name.verbatim(), kind)
        fun line(label: String, value: Any?) {
            if (value != null) sender info toolMsg.infoLine(label, value.toString().verbatim())
        }
        line("title", menu.title)
        line("size", if (menu.type == "chest") "${menu.size} slots" else menu.type)
        line("commands", menu.commands.joinToString().ifEmpty { null })
        line("args", menu.args.joinToString().ifEmpty { null })
        line("items", "${menu.items.size} (${entry.bySlot.size} slots" + (if (entry.inventoryBySlot.isNotEmpty()) ", ${entry.inventoryBySlot.size} inventory slots" else "") + ")")
        menu.matrix?.let { if (it.cells.isNotEmpty() || it.map.isNotEmpty()) line("matrix", "${it.cells.size} rows, ${it.map.size} mapped keys") }
        line("refresh", menu.refresh)
        menu.pages?.let { line("pages", "${it.catalog::class.simpleName}, ${it.listed().size} slots") }
        line("animation", frames?.let { "$it frames" + if (menu.animation!!.loop) ", looping" else "" })
        menu.bedrock?.let { b ->
            val target = b.menu?.let { "redirect -> $it" } ?: b.mode.name
            val scope = listOfNotNull(
                b.devices.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "devices="),
                b.inputs.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "inputs="),
                b.ui.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "ui="),
            ).joinToString(", ")
            line("bedrock", if (scope.isEmpty()) target else "$target ($scope)")
        }
        line("open-with", menu.openWith.joinToString { it::class.simpleName.orEmpty() }.ifEmpty { null })
        line("open gate", menu.open?.let { "${it.require.size} checks" })
        return Command.SINGLE_SUCCESS
    }

    private fun validate(ctx: CommandContext<CommandSourceStack>, name: String?): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val manager = plugin.menus
        if (name != null) {
            val entry = manager.menu(name)
            val failure = manager.failures.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            when {
                failure != null -> sender fail toolMsg.unreadable(failure.key.verbatim(), failure.value.verbatim())
                entry == null -> sender fail menuMsg.unknown(name.verbatim())
                else -> manager.problemsOf(entry).let { found ->
                    if (found.isEmpty()) sender pass toolMsg.noProblems(entry.name.verbatim()) else sender.problems(entry.name, found)
                }
            }
            return Command.SINGLE_SUCCESS
        }
        var bad = 0
        for ([failed, reason] in manager.failures) {
            bad++
            sender fail toolMsg.unreadable(failed.verbatim(), reason.verbatim())
        }
        for (entry in manager.menus.sortedBy { it.name }) {
            val found = manager.problemsOf(entry)
            if (found.isNotEmpty()) bad++
            sender.problems(entry.name, found)
        }
        sender info toolMsg.validated(manager.menus.size + manager.failures.size, bad)
        return Command.SINGLE_SUCCESS
    }

    private fun inspect(ctx: CommandContext<CommandSourceStack>, target: Player?): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        if (target == null) {
            sender fail menuMsg.playerOnly
            return Command.SINGLE_SUCCESS
        }
        val view = plugin.menus.view(target)
        val bedrockSession = if (view == null) plugin.bedrockForms.session(target) else null
        if (view == null && bedrockSession == null) {
            sender fail toolMsg.inspectClosed(target.name)
            return Command.SINGLE_SUCCESS
        }
        if (bedrockSession != null) {
            val profile = Clients.profile(target)
            sender info toolMsg.inspectHeader(target.name, bedrockSession.name.verbatim())
            sender info "client: ${profile.edition.name.lowercase()} (${profile.os.name.lowercase()}, ${profile.input.name.lowercase()}, ${profile.ui.name.lowercase()})".verbatim()
            sender info "bedrock form: ${bedrockSession.presentation::class.simpleName}, page ${bedrockSession.page + 1}".verbatim()
            return Command.SINGLE_SUCCESS
        }
        // The view belongs to the player's region thread; the report is made there and sent from there.
        target.scheduler.run(plugin, {
            val lines = buildList {
                val p = view!!.profile
                add("client: ${p.edition.name.lowercase()} (${p.os.name.lowercase()}, ${p.input.name.lowercase()}, ${p.ui.name.lowercase()})")
                addAll(view.inspect())
            }
            sender info toolMsg.inspectHeader(target.name, view!!.name.verbatim())
            lines.take(toolMsg.inspectShown).forEach { sender info it.verbatim() }
            if (lines.size > toolMsg.inspectShown) {
                sender info toolMsg.inspectMore(lines.size - toolMsg.inspectShown)
                plugin.logger.info(lines.joinToString("\n"))
            }
        }, null)
        return Command.SINGLE_SUCCESS
    }

    private fun client(ctx: CommandContext<CommandSourceStack>, target: Player?): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        if (target == null) {
            sender fail menuMsg.playerOnly
            return Command.SINGLE_SUCCESS
        }
        val profile = Clients.profile(target)
        val dialogs = if (Clients.dialogs(target)) "yes" else if (profile.isBedrock && plugin.bedrockForms.available) "bedrock form" else "no"
        sender info toolMsg.clientInfo(
            target.name,
            profile.edition.name.lowercase(),
            "${profile.os.name.lowercase()}/${profile.device.name.lowercase()}",
            profile.input.name.lowercase(),
            profile.ui.name.lowercase(),
            dialogs,
        )
        return Command.SINGLE_SUCCESS
    }

    /**
     * Prints the link to the web editor. With the endpoint on, the link carries a token for this server; without it, the link
     * carries the menu itself, which needs a name.
     */
    private fun edit(ctx: CommandContext<CommandSourceStack>, menu: String?): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val messages = cmdMsg.edit
        val settings = plugin.config.data.editor
        if (plugin.editor.running) {
            val token = plugin.editor.sessions.create(sender.name)
            val address = settings.publicUrl.ifBlank { "http://${settings.host}:${settings.port}" }
            val link = EditorLink.server(settings.site, address, token, menu)
            sender.sendMessage(linkMessage(messages.linkServer.replace("{minutes}", settings.sessionMinutes.toString()), link))
            return Command.SINGLE_SUCCESS
        }
        if (menu == null) {
            sender fail messages.needsMenu
            return Command.SINGLE_SUCCESS
        }
        val json = plugin.editor.menus.read(menu)?.render(ConfigRenderOptions.concise())
        if (json == null) {
            sender fail menuMsg.unknown(menu.verbatim())
            return Command.SINGLE_SUCCESS
        }
        val link = EditorLink.data(settings.site, menu, json)
        if (link == null) {
            sender fail messages.tooBig(menu.verbatim())
            return Command.SINGLE_SUCCESS
        }
        sender.sendMessage(linkMessage(messages.linkData.replace("{menu}", menu.verbatim()), link))
        sender info messages.dataHint
        return Command.SINGLE_SUCCESS
    }

    /** Prints a shared link to the editor (via relay if configured, otherwise via the direct HTTP endpoint). Saves require admin approval. */
    private fun share(ctx: CommandContext<CommandSourceStack>, menu: String?, readOnly: Boolean): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val messages = cmdMsg.edit
        val settings = plugin.config.data.editor
        val template = if (readOnly) messages.shareLinkReadOnly else messages.shareLink
        if (settings.relay.url.isNotBlank()) {
            // Opening a session is a request to the relay, which waits for an answer, so it is not made on the server's thread.
            plugin.server.asyncScheduler.runNow(plugin) {
                when (val started = plugin.relay.start(sender.name, menu, readOnly)) {
                    is EditorRelay.Started.Failed -> sender fail messages.shareFailed(started.reason)
                    is EditorRelay.Started.Ok -> sender.sendMessage(linkMessage(template.replace("{minutes}", settings.sessionMinutes.toString()), started.link))
                }
            }
            return Command.SINGLE_SUCCESS
        }
        if (plugin.editor.running) {
            val token = plugin.editor.sessions.create(sender.name, shared = true, readOnly = readOnly)
            val address = settings.publicUrl.ifBlank { "http://${settings.host}:${settings.port}" }
            val link = EditorLink.server(settings.site, address, token, menu)
            sender.sendMessage(linkMessage(template.replace("{minutes}", settings.sessionMinutes.toString()), link))
            return Command.SINGLE_SUCCESS
        }
        sender fail messages.shareOff
        return Command.SINGLE_SUCCESS
    }

    private fun stopSharing(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val owner = sender.name.takeIf { sender is Player }
        val direct = plugin.editor.sessions.clearShared(owner)
        plugin.server.asyncScheduler.runNow(plugin) { sender info cmdMsg.edit.shareStopped(direct + plugin.relay.stop(owner)) }
        return Command.SINGLE_SUCCESS
    }

    /** Lists the edits that came through shared links. */
    private fun edits(ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val messages = cmdMsg.edit
        val waiting = plugin.editor.pending.list()
        if (waiting.isEmpty()) { sender info messages.editsNone; return Command.SINGLE_SUCCESS }
        sender info messages.editsHeader(waiting.size)
        val now = System.currentTimeMillis()
        for (edit in waiting) sender info messages.editsLine(edit.id, edit.from.verbatim(), edit.name.verbatim(), edit.problems.size, (now - edit.at) / 60_000)
        return Command.SINGLE_SUCCESS
    }

    /** Writes edit [id] over its menu, as a save of the editor does. */
    private fun applyEdit(ctx: CommandContext<CommandSourceStack>, id: Int): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val messages = cmdMsg.edit
        val edit = plugin.editor.pending.take(id)
        if (edit == null) { sender fail messages.editsUnknown(id); return Command.SINGLE_SUCCESS }
        when (val result = plugin.editor.menus.save(edit.name, edit.menu)) {
            is EditorMenus.Result.Refused -> { sender fail messages.editsRefused(id, result.reason.verbatim()); return Command.SINGLE_SUCCESS }
            is EditorMenus.Result.Ok -> {
                plugin.logger.info("${sender.name} applied edit #$id of '${edit.name}' by ${edit.from}")
                sender pass messages.editsApplied(id, edit.name.verbatim(), "")
                if (result.problems.isNotEmpty()) sender.problems(edit.name, result.problems)
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun dropEdit(ctx: CommandContext<CommandSourceStack>, id: Int): Int {
        val sender = ctx.source.sender
        if (!sender.allowed()) return Command.SINGLE_SUCCESS
        val edit = plugin.editor.pending.take(id)
        sender info (if (edit == null) cmdMsg.edit.editsUnknown(id) else cmdMsg.edit.editsDropped(id))
        return Command.SINGLE_SUCCESS
    }

    /**
     * [template] with `{link}` replaced by [link], which is added as plain text (its `&` and `=` must not be read as formatting)
     * and clicks open the address.
     */
    private fun linkMessage(template: String, link: String): Component {
        val before = template.substringBefore("{link}").toComponent()
        val after = template.substringAfter("{link}", "").toComponent()
        val address = Component.text(link, NamedTextColor.AQUA)
            .clickEvent(ClickEvent.openUrl(link))
            .hoverEvent(HoverEvent.showText(cmdMsg.edit.clickToOpen.toComponent()))
        return before.append(address).append(after)
    }

    /** Opens [menu] for [target], or for the sender if it is a player and no target is given. */
    private fun open(ctx: CommandContext<CommandSourceStack>, menu: String, target: Player?, args: List<String> = emptyList()): Int {
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
        plugin.menus.open(player, menu, args)
        if (player !== sender) sender pass menuMsg.opened(menu.verbatim(), player.name)
        return Command.SINGLE_SUCCESS
    }

    /** The rest of the line is the folder, so a path needs no quoting. */
    private fun folder(overwrite: Boolean) = Commands
        .argument("folder", StringArgumentType.greedyString())
        .executes { import(it, StringArgumentType.getString(it, "folder"), overwrite) }

    private fun CommandSender.help() {
        if (allowed()) this send cmdMsg.help("xalimenu")
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
