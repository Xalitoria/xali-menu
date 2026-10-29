package mc.xalitoria.menu.paper.runtime

import mc.xalitoria.menu.api.MenuContext
import mc.xalitoria.menu.api.model.Gate

/**
 * Whether this gate lets [ctx]'s player through; no gate always does. With [run], the actions of the checks and of the
 * gate itself are run as they pass or fail. That is for opening a menu and for clicks; deciding which item to show
 * must not run anything, since it is asked again on every refresh.
 *
 * Without a `minimum` every check that isn't optional must hold, and the first that doesn't ends the matter.
 * With one, that many checks (optional ones included) must hold, and all are looked at.
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
                if (minimum == null) break
            }
        }
        if (stopAtSuccess && minimum != null && passed >= minimum) break
    }
    val ok = if (minimum != null) passed >= minimum else !failed
    if (run) ctx.run(if (ok) pass else deny)
    return ok
}
