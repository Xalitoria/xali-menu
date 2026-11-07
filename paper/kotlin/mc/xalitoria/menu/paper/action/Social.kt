package mc.xalitoria.menu.paper.action

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.xalitoria.menu.Action
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.model.DurationAsString
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.condition.PlayerConditions
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Prompt

/** The actions that ask the viewer for something, and the ones for the groups and meta data of a permission plugin. */
object Social {
    fun register(registry: TypeRegistry) {
        registry.action<Input>()
        registry.action<Group>()
        registry.action<Meta>()
        registry.action<Cooldown>()
    }

    /**
     * Closes the menu and asks the viewer to type something in chat. What they type is not said in chat; it runs [then] as
     * `{input}`. Typing [cancel] (or waiting longer than [timeout]) runs [onCancel] instead. With [match], a regular
     * expression the whole reply must fit, anything else is turned down with [invalid] and the viewer may try again.
     */
    @Serializable @SerialName("input")
    data class Input(
        val prompt: String,
        val then: List<Action>,
        val cancel: String = "cancel",
        val onCancel: List<Action> = [],
        @Serializable(with = DurationAsString::class)
        val timeout: Duration = 30.seconds,
        val match: String? = null,
        val invalid: String? = null,
    ) : Action {
        init {
            require(then.isNotEmpty()) { "input needs actions to run with the reply" }
            require(cancel.isNotBlank()) { "cancel can't be empty" }
            match?.let { Regex(it) }
        }

        override fun run(ctx: MenuContext) {
            val plugin = XaliMenu.INSTANCE
            val asked = Prompt(ctx as Context, then, onCancel, cancel, match?.let(::Regex), invalid)
            ctx.close()
            plugin.inputs.ask(asked)
            ctx.player.sendMessage(ctx.component(prompt))
            ctx.runLater((timeout.inWholeMilliseconds / 50).coerceAtLeast(1)) { plugin.inputs.expire(asked) }
        }
    }

    /** Puts the viewer in the permission group [name], or with [take] takes them out of it, through Vault. */
    @Serializable @SerialName("group")
    data class Group(val name: String, val take: Boolean = false) : Action {
        override fun run(ctx: MenuContext) {
            if (XaliMenu.economy?.group(ctx.player, ctx.parse(name), take) != true) {
                debug("No permission provider changed the group {0} of {1}", name, ctx.player.name)
                ctx.abort()
            }
        }
    }

    /**
     * Sets the LuckPerms meta [key] of the viewer to [value], or without a value removes it. LuckPerms has to be installed.
     * It is done through its command, which does the saving and the telling of other servers.
     */
    @Serializable @SerialName("meta")
    data class Meta(val key: String, val value: String? = null) : Action {
        override fun run(ctx: MenuContext) {
            val server = ctx.player.server
            if (!server.pluginManager.isPluginEnabled("LuckPerms")) {
                debug("LuckPerms isn't installed, so the meta {0} of {1} stays", key, ctx.player.name)
                ctx.abort()
            }
            val name = ctx.player.name
            val meta = ctx.parse(key).trim()
            require(meta.isNotEmpty() && meta.none { it.isWhitespace() }) { "'$meta' isn't a meta key" }
            ctx.console(if (value == null) "lp user $name meta unset $meta" else "lp user $name meta set $meta ${quote(ctx.parse(value))}")
        }
    }

    /**
     * Starts the cooldown [key] for the viewer (or for everybody with [scope] `GLOBAL`), which the `cooldown` condition
     * with the same key holds off until [duration] has passed. Without a [duration] it never ends, which makes a thing
     * that can be done once. The state is the variable `cooldown_<key>`, so it is kept where the variables are.
     */
    @Serializable @SerialName("cooldown")
    data class Cooldown(
        val key: String,
        @Serializable(with = DurationAsString::class)
        val duration: Duration? = null,
        val scope: PlayerConditions.VariableExists.Scope = PlayerConditions.VariableExists.Scope.PLAYER,
    ) : Action {
        init {
            require(key.isNotBlank()) { "a cooldown needs a key" }
            require(duration == null || duration.isPositive()) { "duration must be positive" }
        }

        override fun run(ctx: MenuContext) {
            val variables = XaliMenu.INSTANCE.variables
            val name = PlayerConditions.variable(ctx.parse(key))
            val owner = if (scope == PlayerConditions.VariableExists.Scope.GLOBAL) net.xalitoria.menu.Scope.Global
            else net.xalitoria.menu.Scope.Player(ctx.player.uniqueId)
            val until = duration?.let { java.time.Instant.now().plusMillis(it.inWholeMilliseconds) }
            if (!variables.isAvailable(name) || !variables.set(owner, name, "1", until, true)) {
                debug("Could not start the cooldown {0} for {1}", name, ctx.player.name)
                ctx.abort()
            }
        }
    }

    /** [value] as one argument of a command: in quotes if it has a space, with the quotes in it taken out. */
    fun quote(value: String): String {
        val clean = value.replace("\"", "").filter { !it.isISOControl() }
        return if (clean.any { it.isWhitespace() } || clean.isEmpty()) "\"$clean\"" else clean
    }
}
