package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.model.Gate

/**
 * Whether this gate lets [ctx]'s player through; no gate always does. With [run], the actions of the checks and of the
 * gate itself are run as they pass or fail. That is for opening a menu and for clicks; deciding which item to show
 * must not run anything, since it is asked again on every refresh.
 *
 * A check that is not optional must hold, and the first that does not ends the matter. An optional one that fails is let
 * through, unless the `minimum` is more than the checks that held (optional ones count, and non-optional ones too).
 */
fun Gate?.passes(ctx: MenuContext, run: Boolean): Boolean {
    if (this == null) return true
    val minimum = minimum
    var passed = 0
    var failed = false
    for (check in require) {
        if (check.condition.test(ctx)) {
            passed++
            if (run) ctx.run(check.pass)
        } else {
            if (run) ctx.run(check.deny)
            if (!check.optional) {
                failed = true
                break
            }
        }
        if (stopAtSuccess && minimum != null && passed >= minimum) break
    }
    val ok = !failed && passed >= (minimum ?: 0)
    if (run) ctx.run(if (ok) pass else deny)
    return ok
}

/**
 * How each check of this gate comes out for [ctx]'s player, for a person working out why something is or is not shown.
 * Nothing is run. A gate that is not there has no checks.
 */
fun Gate?.explain(ctx: MenuContext): List<String> = this?.require.orEmpty().map { check ->
    val result = if (check.condition.test(ctx)) "holds" else "FAILS"
    "$result${if (check.optional) " (optional)" else ""}: ${check.condition}"
}
