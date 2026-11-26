package fixture.ext.broken

import fixture.shared.Seen
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActionType
import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.MenuExtension

class Half : Action {
    override fun run(ctx: MenuContext) = Unit
}

class BrokenExtension : MenuExtension() {
    override val identifier = "broken"
    override val author = "tests"
    override val version = "1"

    override fun register(context: ExtensionContext) {
        context.registerAction("half", ActionType { Half() })
        context.registerListener(object : org.bukkit.event.Listener {})
        error("boom")
    }

    override fun unregister() {
        Seen.log += "broken unregistered"
    }
}
