package fixture.ext.badid

import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuExtension

class BadIdExtension : MenuExtension() {
    override val identifier = "Bad Id"
    override val author = "tests"
    override val version = "1"

    override fun register(context: ExtensionContext) = error("never called")
}
