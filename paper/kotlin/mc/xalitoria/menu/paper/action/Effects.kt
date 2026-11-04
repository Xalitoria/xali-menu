package mc.xalitoria.menu.paper.action

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.model.DurationAsString
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.api.TypeRegistry
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Registry
import org.bukkit.attribute.Attribute

/** The actions that change the viewer: their body, their position, what they see. */
object Effects {
    fun register(registry: TypeRegistry) {
        registry.action<Potion>()
        registry.action<Teleport>()
        registry.action<Gamemode>()
        registry.action<Health>()
        registry.action<Food>()
        registry.action<Levels>()
        registry.action<ShowTitle>()
        registry.action<ShowBossBar>()
        registry.action<OpenBook>()
        registry.action<ShowParticle>()
        registry.action<Skin>()
        registry.action<SkinReset>()
    }

    /** How a number is changed by an amount. */
    enum class Change {
        SET, ADD, TAKE;

        /** [current] changed by [amount], held between 0 and [max]. */
        fun apply(current: Double, amount: Double, max: Double): Double = when (this) {
            SET -> amount
            ADD -> current + amount
            TAKE -> current - amount
        }.coerceIn(0.0, max)
    }

    /**
     * A coordinate as written in a command: a number, or `~` (where the player is) with an offset, `~5` or `~-2.5`.
     * @return null if [text] is neither
     */
    fun coordinate(text: String, current: Double): Double? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("~")) return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
        val offset = trimmed.substring(1)
        return if (offset.isEmpty()) current else offset.toDoubleOrNull()?.takeIf { it.isFinite() }?.plus(current)
    }

    /** Gives the viewer a potion effect, or with [remove] takes [effect] off them (every effect when [effect] is `all`). */
    @Serializable @SerialName("potion")
    data class Potion(
        val effect: String,
        @Serializable(with = DurationAsString::class)
        val duration: Duration = 30.seconds,
        val amplifier: Int = 0,
        val particles: Boolean = true,
        val remove: Boolean = false,
    ) : Action {
        init {
            require(amplifier in 0..255) { "amplifier must be between 0 and 255" }
        }

        override fun run(ctx: MenuContext) {
            val player = ctx.player
            val name = ctx.parse(effect).trim().lowercase()
            if (remove && name == "all") return player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            val type = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(Key.key(name))
                ?: throw IllegalArgumentException("'$name' isn't a potion effect")
            if (remove) return player.removePotionEffect(type).let { }
            val ticks = duration.inWholeMilliseconds / 50
            player.addPotionEffect(org.bukkit.potion.PotionEffect(type, ticks.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), amplifier, false, particles))
        }
    }

    /**
     * Moves the viewer to [x], [y], [z] in [world] (the one they are in without it), looking [yaw] and [pitch]. Each may be
     * a placeholder or `{name}`, and a coordinate may be `~` or `~5` for relative to where they are now. A coordinate
     * that is left out stays as it is.
     */
    @Serializable @SerialName("teleport")
    data class Teleport(
        val x: String = "~",
        val y: String = "~",
        val z: String = "~",
        val world: String? = null,
        val yaw: String = "~",
        val pitch: String = "~",
    ) : Action {
        override fun run(ctx: MenuContext) {
            val player = ctx.player
            val here = player.location
            val target = world?.let(ctx::parse)?.trim()?.let { name ->
                player.server.getWorld(name) ?: throw IllegalArgumentException("There is no world '$name'")
            } ?: here.world
            fun at(text: String, current: Double, what: String) =
                coordinate(ctx.parse(text), current) ?: throw IllegalArgumentException("'$text' isn't a $what")
            val there = Location(
                target,
                at(x, here.x, "x"), at(y, here.y, "y"), at(z, here.z, "z"),
                at(yaw, here.yaw.toDouble(), "yaw").toFloat(), at(pitch, here.pitch.toDouble(), "pitch").toFloat(),
            )
            player.teleportAsync(there)
        }
    }

    /** Puts the viewer in a game mode: `survival`, `creative`, `adventure` or `spectator`. */
    @Serializable @SerialName("gamemode")
    data class Gamemode(val mode: String) : Action {
        override fun run(ctx: MenuContext) {
            val name = ctx.parse(mode).trim()
            ctx.player.gameMode = GameMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("'$name' isn't a game mode")
        }
    }

    /** Sets the viewer's health to [amount], or with [change] adds or takes that much. It stays between 0 and their maximum. */
    @Serializable @SerialName("health")
    data class Health(val amount: String, val change: Change = Change.SET) : Action {
        override fun run(ctx: MenuContext) {
            val value = ctx.parse(amount).trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$amount' isn't a number")
            val player = ctx.player
            val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            player.health = change.apply(player.health, value, max)
        }
    }

    /** Sets the viewer's food level (0 to 20) to [amount], or with [change] adds or takes that much. */
    @Serializable @SerialName("food")
    data class Food(val amount: String, val change: Change = Change.SET, val saturation: String? = null) : Action {
        override fun run(ctx: MenuContext) {
            val value = ctx.parse(amount).trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$amount' isn't a number")
            val player = ctx.player
            player.foodLevel = change.apply(player.foodLevel.toDouble(), value, 20.0).toInt()
            saturation?.let { text ->
                val level = ctx.parse(text).trim().toFloatOrNull() ?: throw IllegalArgumentException("'$text' isn't a number")
                player.saturation = level.coerceIn(0f, player.foodLevel.toFloat())
            }
        }
    }

    /** Gives the viewer [amount] experience levels, or with [take] takes that many (never below 0). */
    @Serializable @SerialName("level")
    data class Levels(val amount: String, val take: Boolean = false) : Action {
        override fun run(ctx: MenuContext) {
            val value = ctx.parse(amount).trim().toIntOrNull() ?: throw IllegalArgumentException("'$amount' isn't a whole number")
            val player = ctx.player
            if (take && player.level < value) {
                debug("{0} has fewer than {1} levels", player.name, value)
                ctx.abort()
            }
            player.giveExpLevels(if (take) -value else value)
        }
    }

    /** Shows the viewer a title and a subtitle in the middle of the screen. The times are in ticks, as the game's `/title`. */
    @Serializable @SerialName("title")
    data class ShowTitle(
        val title: String = "",
        val subtitle: String = "",
        val fadeIn: Int = 10,
        val stay: Int = 70,
        val fadeOut: Int = 20,
    ) : Action {
        init {
            require(title.isNotEmpty() || subtitle.isNotEmpty()) { "title needs a title or a subtitle" }
            require(fadeIn >= 0 && stay >= 0 && fadeOut >= 0) { "the times can't be negative" }
        }

        override fun run(ctx: MenuContext) {
            val times = Title.Times.times(ticks(fadeIn), ticks(stay), ticks(fadeOut))
            ctx.player.showTitle(Title.title(ctx.component(title), ctx.component(subtitle), times))
        }

        private fun ticks(count: Int): JavaDuration = JavaDuration.ofMillis(count * 50L)
    }

    /** Shows the viewer a boss bar with [text] for [duration], then takes it away. */
    @Serializable @SerialName("boss-bar")
    data class ShowBossBar(
        val text: String,
        val color: BossBar.Color = BossBar.Color.PURPLE,
        val style: BossBar.Overlay = BossBar.Overlay.PROGRESS,
        val progress: Float = 1f,
        @Serializable(with = DurationAsString::class)
        val duration: Duration = 5.seconds,
    ) : Action {
        init {
            require(progress in 0f..1f) { "progress is a fraction between 0 and 1" }
        }

        override fun run(ctx: MenuContext) {
            val player = ctx.player
            val bar = BossBar.bossBar(ctx.component(text), progress, color, style)
            player.showBossBar(bar)
            ctx.runLater((duration.inWholeMilliseconds / 50).coerceAtLeast(1)) { player.hideBossBar(bar) }
        }
    }

    /** Opens a written book for the viewer, one entry of [pages] to a page. */
    @Serializable @SerialName("book")
    data class OpenBook(val pages: List<String>, val title: String = "Book", val author: String = "") : Action {
        init {
            require(pages.isNotEmpty()) { "a book needs a page" }
        }

        override fun run(ctx: MenuContext) {
            ctx.player.openBook(Book.book(ctx.component(title), ctx.component(author), pages.map<String, Component>(ctx::component)))
        }
    }

    /**
     * Shows a particle at the viewer's feet, moved by [dx], [dy], [dz]. [count] of them are spread by [spread] blocks. With
     * [everyone] the players around see it too. Only particles that need no extra data work.
     */
    @Serializable @SerialName("particle")
    data class ShowParticle(
        val particle: String,
        val count: Int = 10,
        val spread: Double = 0.5,
        val speed: Double = 0.0,
        val dx: Double = 0.0,
        val dy: Double = 1.0,
        val dz: Double = 0.0,
        val everyone: Boolean = false,
    ) : Action {
        init {
            require(count in 1..1000) { "count must be between 1 and 1000" }
        }

        override fun run(ctx: MenuContext) {
            val player = ctx.player
            val name = ctx.parse(particle).trim().lowercase()
            val type = Registry.PARTICLE_TYPE.get(Key.key(name)) ?: throw IllegalArgumentException("'$name' isn't a particle")
            val at = player.location.add(dx, dy, dz)
            if (everyone) player.world.spawnParticle(type, at, count, spread, spread, spread, speed)
            else player.spawnParticle(type, at, count, spread, spread, spread, speed)
        }
    }

    /**
     * Gives the viewer the skin of the player [skin], or of the picture at that link, with SkinsRestorer, which keeps it for
     * their next visit. [slim] picks the thinner arms for a picture. It is looked up in the background and put on when found,
     * so the actions after it do not wait; one that cannot be found is noted in the console.
     */
    @Serializable @SerialName("skin")
    data class Skin(val skin: String, val slim: Boolean = false) : Action {
        override fun run(ctx: MenuContext) {
            val skins = XaliMenu.INSTANCE.skins
            if (skins == null) {
                debug("SkinsRestorer isn't installed, so the skin of {0} stays", ctx.player.name)
                ctx.abort()
                return
            }
            val source = ctx.parse(skin).trim()
            skins.set(ctx.player, source, slim) { XaliMenu.INSTANCE.logger.warning("Skin '$source' for ${ctx.player.name}: $it") }
        }
    }

    /** Takes the skin a menu gave the viewer off them again, with SkinsRestorer. */
    @Serializable @SerialName("skin-reset")
    data object SkinReset : Action {
        override fun run(ctx: MenuContext) {
            val skins = XaliMenu.INSTANCE.skins
            if (skins == null) {
                debug("SkinsRestorer isn't installed, so the skin of {0} stays", ctx.player.name)
                ctx.abort()
                return
            }
            skins.reset(ctx.player) { XaliMenu.INSTANCE.logger.warning("Resetting the skin of ${ctx.player.name}: $it") }
        }
    }
}
