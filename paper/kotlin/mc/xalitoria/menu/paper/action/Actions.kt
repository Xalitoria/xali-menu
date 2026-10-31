package mc.xalitoria.menu.paper.action

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.model.DurationAsString
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.util.verbatim
import mc.xalitoria.util.message.fail
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound as AdventureSound
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Registry

/**
 * The actions XaliMenu comes with. Text may hold colors (legacy codes, hex, CMI gradients, MiniMessage) and placeholders:
 * PlaceholderAPI's, and `{name}` for the menu's arguments.
 */
object Actions {
    fun register(registry: TypeRegistry) {
        registry.action<Player>()
        registry.action<Console>()
        registry.action<Chat>()
        registry.action<Message>()
        registry.action<Broadcast>()
        registry.action<ActionBar>()
        registry.action<Json>()
        registry.action<Open>()
        registry.action<Close>()
        registry.action<Refresh>()
        registry.action<Connect>()
        registry.action<Money>()
        registry.action<Experience>()
        registry.action<Permission>()
        registry.action<Sound>()
        registry.action<Delay>()
        registry.action<Chance>()
        registry.action<Stop>()
        registry.action<Variable>()
    }

    /** The viewer runs [command]. */
    @Serializable @SerialName("player")
    data class Player(val command: String) : Action {
        override fun run(ctx: MenuContext) {
            ctx.player.performCommand(ctx.parse(command))
        }
    }

    /** The console runs [command]. */
    @Serializable @SerialName("console")
    data class Console(val command: String) : Action {
        override fun run(ctx: MenuContext) = ctx.console(ctx.parse(command))
    }

    /** The viewer says [text] in chat. */
    @Serializable @SerialName("chat")
    data class Chat(val text: String) : Action {
        override fun run(ctx: MenuContext) = ctx.player.chat(ctx.parse(text))
    }

    @Serializable @SerialName("message")
    data class Message(val text: String) : Action {
        override fun run(ctx: MenuContext) {
            ctx.player.sendMessage(ctx.component(text))
        }
    }

    @Serializable @SerialName("broadcast")
    data class Broadcast(val text: String) : Action {
        override fun run(ctx: MenuContext) {
            ctx.player.server.broadcast(ctx.component(text))
        }
    }

    @Serializable @SerialName("actionbar")
    data class ActionBar(val text: String) : Action {
        override fun run(ctx: MenuContext) = ctx.player.sendActionBar(ctx.component(text))
    }

    /** A component written as JSON, shown to the viewer or, with [broadcast], to everyone. */
    @Serializable @SerialName("json")
    data class Json(val json: String, val broadcast: Boolean = false) : Action {
        override fun run(ctx: MenuContext) {
            val component = GsonComponentSerializer.gson().deserialize(ctx.parse(json) { it.replace("\\", "\\\\").replace("\"", "\\\"") })
            if (broadcast) ctx.player.server.broadcast(component) else ctx.player.sendMessage(component)
        }
    }

    /** Opens the menu [menu], handing it [args] for its own `args`. */
    @Serializable @SerialName("open")
    data class Open(val menu: String, val args: List<String> = []) : Action {
        override fun run(ctx: MenuContext) = ctx.open(menu, args.map(ctx::parse))
    }

    @Serializable @SerialName("close")
    data object Close : Action {
        override fun run(ctx: MenuContext) = ctx.close()
    }

    /** Draws the open menu again, so items that depend on placeholders or requirements are re-evaluated. */
    @Serializable @SerialName("refresh")
    data object Refresh : Action {
        override fun run(ctx: MenuContext) = ctx.refresh()
    }

    /** Sends the viewer to another server of the proxy. */
    @Serializable @SerialName("connect")
    data class Connect(val server: String) : Action {
        override fun run(ctx: MenuContext) {
            val bytes = ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeUTF("Connect")
                    writeUTF(ctx.parse(server))
                }
            }
            ctx.player.sendPluginMessage(XaliMenu.INSTANCE, "BungeeCord", bytes.toByteArray())
        }
    }

    /**
     * Pays [amount] to the viewer, or with [take] charges it. [amount] may be a placeholder. [currency] picks one of
     * the economy's currencies (VaultUnlocked has several); without it the default one is used.
     */
    @Serializable @SerialName("money")
    data class Money(val amount: String, val take: Boolean = false, val currency: String? = null) : Action {
        override fun run(ctx: MenuContext) {
            val value = ctx.parse(amount).trim().toBigDecimalOrNull() ?: return
            val economy = XaliMenu.economy
            val done = when {
                economy == null -> false
                take -> economy.withdraw(ctx.player, value, currency)
                else -> economy.deposit(ctx.player, value, currency)
            }
            if (!done) {
                debug("Could not {0} {1} for {2}", if (take) "take" else "give", value, ctx.player.name)
                ctx.abort()
            }
        }
    }

    @Serializable @SerialName("experience")
    data class Experience(val amount: String, val take: Boolean = false) : Action {
        override fun run(ctx: MenuContext) {
            val value = ctx.parse(amount).trim().toIntOrNull() ?: return
            val player = ctx.player
            if (take && player.calculateTotalExperiencePoints() < value) {
                debug("{0} has less than {1} experience", player.name, value)
                ctx.abort()
            }
            player.giveExp(if (take) -value else value)
        }
    }

    /** Gives the viewer [node], or with [take] removes it, through whatever provides Vault's permissions. */
    @Serializable @SerialName("permission")
    data class Permission(val node: String, val take: Boolean = false) : Action {
        override fun run(ctx: MenuContext) {
            if (XaliMenu.economy?.permission(ctx.player, ctx.parse(node), take) != true) {
                debug("No permission provider took {0} for {1}", node, ctx.player.name)
                ctx.abort()
            }
        }
    }

    @Serializable @SerialName("sound")
    data class Sound(
        val sound: String,
        val volume: Float = 1f,
        val pitch: Float = 1f,
        val scope: Scope = Scope.PLAYER,
    ) : Action {
        /** Who hears the sound. */
        enum class Scope { PLAYER, WORLD, SERVER }

        override fun run(ctx: MenuContext) {
            val name = ctx.parse(sound)
            val key = key(name) ?: return debug("'{0}' isn't a sound", name)
            val sound = AdventureSound.sound(key, AdventureSound.Source.MASTER, volume, pitch)
            val player = ctx.player
            when (scope) {
                PLAYER -> player.playSound(sound)
                WORLD -> player.world.playSound(sound)
                SERVER -> player.server.playSound(sound)
            }
        }

        /** `entity.player.levelup`, `minecraft:entity.player.levelup`, or the old `ENTITY_PLAYER_LEVELUP`. */
        @Suppress("DEPRECATION") // `Registry.match` is the lenient lookup for the plain names menus use (`ENTITY_PLAYER_LEVELUP`)
        private fun key(name: String): Key? {
            val trimmed = name.trim()
            if (':' in trimmed || '.' in trimmed) return runCatching { Key.key(trimmed.lowercase()) }.getOrNull()
            return Registry.SOUNDS.match(trimmed)?.let { Registry.SOUNDS.getKey(it) }
                ?: runCatching { Key.key(trimmed.lowercase().replace('_', '.')) }.getOrNull()
        }
    }

    /** Runs [action] after [ticks]. */
    @Serializable @SerialName("delay")
    data class Delay(val ticks: Long, val action: Action) : Action {
        override fun run(ctx: MenuContext) = ctx.runLater(ticks) { ctx.run(listOf(action)) }
    }

    /** Runs [action] with a chance of [percent] (0 to 100). If it aborts, so does the list this is in. */
    @Serializable @SerialName("chance")
    data class Chance(val percent: Double, val action: Action) : Action {
        override fun run(ctx: MenuContext) {
            if (Random.nextDouble() * 100 < percent) action.run(ctx)
        }
    }

    /** Ends the list of actions it is in; the ones after it don't run. Mostly for a `chance`. */
    @Serializable @SerialName("stop")
    data object Stop : Action {
        override fun run(ctx: MenuContext) = ctx.abort()
    }

    /**
     * Changes a variable. [op] says how: set it to [value], remove it, or change its number by [value]. It is a global
     * variable, or with [scope] `PLAYER` the personal one of the viewer, or of [player] (a name; may be a placeholder).
     * [expiry] makes a set variable last that long; with [replace] false a variable that already exists is kept. If the
     * variable's store isn't ready yet the viewer is told so and nothing changes.
     */
    @Serializable @SerialName("variable")
    data class Variable(
        val name: String,
        val op: Op = Op.SET,
        val value: String = "",
        val scope: Scope = Scope.GLOBAL,
        val player: String? = null,
        @Serializable(with = DurationAsString::class)
        val expiry: Duration? = null,
        val replace: Boolean = true,
    ) : Action {
        enum class Op { SET, REMOVE, INC, DEC, MUL, DIV }
        enum class Scope { GLOBAL, PLAYER }

        override fun run(ctx: MenuContext) {
            val plugin = XaliMenu.INSTANCE
            val variables = plugin.variables
            val key = ctx.parse(name)
            if (!variables.isAvailable(key)) {
                ctx.player fail plugin.messageConfig.data.commands.variable.unavailable(key.verbatim())
                return
            }
            val owner: mc.xalitoria.menu.api.Scope = if (scope == Scope.GLOBAL) mc.xalitoria.menu.api.Scope.Global else {
                val named = player?.let(ctx::parse)?.trim()?.ifEmpty { null }
                val id = if (named == null) ctx.player.uniqueId
                else ctx.player.server.getPlayerExact(named)?.uniqueId ?: ctx.player.server.getOfflinePlayerIfCached(named)?.uniqueId
                    ?: return debug("Variable {0}: no player named {1}", key, named)
                mc.xalitoria.menu.api.Scope.Player(id)
            }
            val text = ctx.parse(value)
            when (op) {
                Op.SET -> {
                    val until = expiry?.let { java.time.Instant.now().plusMillis(it.inWholeMilliseconds) }
                    variables.set(owner, key, text, until, replace)
                }
                Op.REMOVE -> variables.remove(owner, key)
                else -> {
                    val amount = mc.xalitoria.menu.api.Variable.parseNumber(text) ?: return debug("Variable {0}: '{1}' isn't a number", key, text)
                    val change: (BigDecimal) -> BigDecimal = when (op) {
                        Op.INC -> { current -> current + amount }
                        Op.DEC -> { current -> current - amount }
                        Op.MUL -> { current -> (current * amount).setScale(PRECISION, RoundingMode.HALF_UP) }
                        else -> { current -> if (amount.signum() == 0) current else current.divide(amount, PRECISION, RoundingMode.HALF_UP) }
                    }
                    variables.change(owner, key, change)
                }
            }
        }
    }

    /** Decimal places a product or quotient of a variable is kept to. */
    private const val PRECISION = 8
}
