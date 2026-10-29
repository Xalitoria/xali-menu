package mc.xalitoria.menu.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.time.Duration
import mc.xalitoria.menu.api.Scope
import mc.xalitoria.menu.api.Variable
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.util.suggestMatching
import mc.xalitoria.menu.paper.util.verbatim
import mc.xalitoria.util.message.*
import org.bukkit.command.CommandSender

/**
 * `/xvar get|set|rem|inc|dec|mul|div <name> ...` for the global variables and `/xvarp <player> get|set|...` for the
 * personal ones of a player, who may be offline if they have been on the server. `set` takes a time after the value
 * (`30m`, `1h`, `2d`) to make the variable expire, and `true`/`false` after that for whether to replace one that exists.
 */
object VariableCommand {
    private val plugin get() = XaliMenu.INSTANCE
    private val variables get() = plugin.variables
    private val msg get() = plugin.messageConfig.data.commands.variable
    private val noPermission get() = plugin.messageConfig.data.commands.noPermission

    fun register(registrar: Commands) {
        registrar.register(global().build(), "Reads and changes global variables", listOf("var"))
        registrar.register(personal().build(), "Reads and changes the variables of a player", listOf("varp"))
    }

    private fun global(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal("xvar")
        .executes { ctx -> help(ctx.source.sender) }
        .also { root -> operations { _ -> Scope.Global }.forEach(root::then) }

    private fun personal(): LiteralArgumentBuilder<CommandSourceStack> = Commands
        .literal("xvarp")
        .executes { ctx -> help(ctx.source.sender) }
        .then(Commands
            .argument("player", StringArgumentType.word())
            .suggests { _, builder -> builder.suggestMatching(plugin.server.onlinePlayers.map { it.name }) }
            .executes { ctx -> help(ctx.source.sender) }
            .also { node ->
                operations { ctx ->
                    val name = StringArgumentType.getString(ctx, "player")
                    val id = plugin.server.getPlayerExact(name)?.uniqueId ?: plugin.server.getOfflinePlayerIfCached(name)?.uniqueId
                    if (id == null) ctx.source.sender fail msg.unknownPlayer(name.verbatim())
                    id?.let { Scope.Player(it) }
                }.forEach(node::then)
            }
        )

    private fun help(sender: CommandSender): Int {
        if (!sender.hasPermission(Permissions.ADMIN)) sender fail noPermission else sender send msg.help()
        return Command.SINGLE_SUCCESS
    }

    /** The subcommands, which act on what [scope] says (null if it has already told the sender why there is none). */
    private fun operations(scope: (CommandContext<CommandSourceStack>) -> Scope?): List<ArgumentBuilder<CommandSourceStack, *>> {
        fun name() = Commands.argument("name", StringArgumentType.word())
        fun names(ctx: CommandContext<CommandSourceStack>) = scope(ctx)?.let(variables::names).orEmpty()

        /** Runs [action] on the variable [label] if the sender may, there is a scope, and the variable's store is ready. */
        fun run(ctx: CommandContext<CommandSourceStack>, label: String, action: (CommandSender, Scope) -> Unit): Int {
            val sender = ctx.source.sender
            if (!sender.hasPermission(Permissions.ADMIN)) {
                sender fail noPermission
                return Command.SINGLE_SUCCESS
            }
            val owner = scope(ctx) ?: return Command.SINGLE_SUCCESS
            if (!variables.isAvailable(label)) sender fail msg.unavailable(label.verbatim()) else action(sender, owner)
            return Command.SINGLE_SUCCESS
        }

        fun numeric(literal: String, change: (BigDecimal, BigDecimal) -> BigDecimal) = Commands.literal(literal).then(
            name()
                .suggests { ctx, builder -> builder.suggestMatching(names(ctx)) }
                .then(Commands.argument("amount", StringArgumentType.word()).executes { ctx ->
                    val label = StringArgumentType.getString(ctx, "name")
                    val amount = Variable.parseNumber(StringArgumentType.getString(ctx, "amount"))
                    run(ctx, label) { sender, owner ->
                        if (amount != null && variables.change(owner, label) { change(it, amount) }) {
                            sender pass msg.changed(label.verbatim(), variables[owner, label]?.value.orEmpty().verbatim())
                        } else sender fail msg.notANumber(label.verbatim())
                    }
                })
        )

        val get = Commands.literal("get").then(
            name().suggests { ctx, builder -> builder.suggestMatching(names(ctx)) }.executes { ctx ->
                val label = StringArgumentType.getString(ctx, "name")
                run(ctx, label) { sender, owner ->
                    val value = variables[owner, label]?.value
                    if (value == null) sender warn msg.unset(label.verbatim()) else sender info msg.got(label.verbatim(), value.verbatim())
                }
            }
        )

        fun set(ctx: CommandContext<CommandSourceStack>, time: String?, replace: Boolean): Int {
            val label = StringArgumentType.getString(ctx, "name")
            val value = StringArgumentType.getString(ctx, "value")
            val duration = time?.let { Duration.parseOrNull(it) }
            if (time != null && (duration == null || !duration.isPositive())) {
                ctx.source.sender fail msg.invalidTime(time.verbatim())
                return Command.SINGLE_SUCCESS
            }
            debug("{0} sets {1} to '{2}' (expiry {3}, replace {4})", ctx.source.sender.name, label, value, time, replace)
            return run(ctx, label) { sender, owner ->
                val expiry = duration?.let { Instant.now().plusMillis(it.inWholeMilliseconds) }
                if (variables.set(owner, label, value, expiry, replace)) sender pass msg.set(label.verbatim(), value.verbatim())
                else sender warn msg.kept(label.verbatim())
            }
        }

        val set = Commands.literal("set").then(
            name().suggests { ctx, builder -> builder.suggestMatching(names(ctx)) }.then(
                Commands.argument("value", StringArgumentType.string())
                    .executes { ctx -> set(ctx, null, replace = true) }
                    .then(Commands.argument("time", StringArgumentType.word())
                        .executes { ctx -> set(ctx, StringArgumentType.getString(ctx, "time"), replace = true) }
                        .then(Commands.argument("replace", BoolArgumentType.bool()).executes { ctx ->
                            set(ctx, StringArgumentType.getString(ctx, "time"), BoolArgumentType.getBool(ctx, "replace"))
                        })
                    )
            )
        )

        val rem = Commands.literal("rem").then(
            name().suggests { ctx, builder -> builder.suggestMatching(names(ctx)) }.executes { ctx ->
                val label = StringArgumentType.getString(ctx, "name")
                run(ctx, label) { sender, owner ->
                    if (variables.remove(owner, label)) sender pass msg.removed(label.verbatim()) else sender warn msg.missing(label.verbatim())
                }
            }
        )

        return listOf(
            get, set, rem,
            numeric("inc") { a, b -> a + b },
            numeric("dec") { a, b -> a - b },
            numeric("mul") { a, b -> (a * b).setScale(8, RoundingMode.HALF_UP) },
            numeric("div") { a, b -> if (b.signum() == 0) a else a.divide(b, 8, RoundingMode.HALF_UP) },
        )
    }
}
