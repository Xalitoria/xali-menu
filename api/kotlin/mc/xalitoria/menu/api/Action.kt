package mc.xalitoria.menu.api

/**
 * One step of a click, open or close sequence. In a menu file it is an object whose `type` names it:
 * `{ type = player, command = "spawn" }`.
 *
 * An action is a plain class that does its work in [run]. Add your own with [XaliMenuApi.registerAction], which takes
 * the function that builds it from the keys written in the menu file:
 *
 * ```kotlin
 * class Reward(private val points: Int) : Action {
 *     override fun run(ctx: MenuContext) = MyPlugin.give(ctx.player, points)
 * }
 *
 * XaliMenuApi.get().registerAction(plugin, "myplugin:reward") { settings -> Reward(settings.int("points", 1)) }
 * ```
 */
interface Action {
    /**
     * Does this step. It is called on the region thread of [MenuContext.player] (Folia); anything that has to happen
     * elsewhere, or later, goes through the context ([MenuContext.runLater], [MenuContext.console]).
     */
    fun run(ctx: MenuContext)
}

/** Thrown by [MenuContext.abort] and caught where a list of actions is run; nothing else should catch it. */
class ActionsAborted : RuntimeException(null, null, false, false)
