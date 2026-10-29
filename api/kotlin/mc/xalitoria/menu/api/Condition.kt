package mc.xalitoria.menu.api

/**
 * A test on the viewer. In a menu file it is an object whose `type` names it, such as
 * `{ type = permission, node = "xalimenu.vip" }`. Add your own with [XaliMenuApi.registerCondition], the same way as an
 * [Action].
 */
interface Condition {
    /**
     * Whether this holds for [MenuContext.player]. It is asked on the player's region thread, and again on every
     * refresh of a menu for the conditions that decide which item to show, so it must be cheap and must not change
     * anything.
     */
    fun test(ctx: MenuContext): Boolean
}
