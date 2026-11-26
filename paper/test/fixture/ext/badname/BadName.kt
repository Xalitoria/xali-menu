package fixture.ext.badname

import net.xalitoria.menu.Action
import net.xalitoria.menu.ActionType
import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.MenuExtension

class BadNameExtension : MenuExtension() {
    override val identifier = "badname"
    override val author = "tests"
    override val version = "1"

    override fun register(context: ExtensionContext) {
        context.registerAction("other:thing", ActionType { object : Action { override fun run(ctx: MenuContext) = Unit } })
    }
}
