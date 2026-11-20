package mc.xalitoria.menu.paper.action

import net.xalitoria.menu.model.Within
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.numbered
import net.xalitoria.menu.Action
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.MenuContext

/** The actions that run other actions: in a bunch, by chance, or once for each element of a catalog. */
object Flow {
    fun register(registry: TypeRegistry) {
        registry.action<All>()
        registry.action<Pick>()
        registry.action<RunFor>()
        registry.action<Log>()
    }

    /** Runs [actions] in order, as a unit: one that stops ends this list only, and the list it is in goes on. */
    @Serializable @SerialName("all")
    data class All(val actions: List<Action>) : Action {
        override fun run(ctx: MenuContext) = ctx.run(actions)
    }

    /** Runs [count] of the [actions], picked at random and each at most once. */
    @Serializable @SerialName("random")
    data class Pick(val actions: List<Action>, @Within(min = 1, strict = true) val count: Int = 1) : Action {
        init {
            require(actions.isNotEmpty()) { "random needs actions to pick from" }
            require(count >= 1) { "random picks at least one" }
        }

        override fun run(ctx: MenuContext) = ctx.run(actions.shuffled().take(count))
    }

    /**
     * Runs [actions] once for each element of [catalog], with the element's values as `{name}` (and `{index}`, from 1). The
     * first [limit] elements are used. Each run is a list of its own, so one that stops does not end the others.
     */
    @Serializable @SerialName("run-for")
    data class RunFor(val catalog: Catalog, val actions: List<Action>, @Within(min = 1, strict = true) val limit: Int = 100) : Action {
        init {
            require(limit >= 1) { "limit must be at least 1" }
        }

        override fun run(ctx: MenuContext) {
            for (element in numbered(catalog.elements(ctx)).take(limit)) {
                val scoped = (ctx as? Context)?.with(element) ?: ctx
                scoped.run(actions)
            }
        }
    }

    /** Writes [text] to the server's log. */
    @Serializable @SerialName("log")
    data class Log(val text: String, val level: Level = Level.INFO) : Action {
        enum class Level { INFO, WARNING, ERROR }

        override fun run(ctx: MenuContext) {
            val logger = XaliMenu.INSTANCE.logger
            val line = "[${ctx.menu ?: "-"}] ${ctx.player.name}: ${ctx.parse(text)}"
            when (level) {
                Level.INFO -> logger.info(line)
                Level.WARNING -> logger.warning(line)
                Level.ERROR -> logger.severe(line)
            }
        }
    }
}
