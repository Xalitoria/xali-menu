package fixture.ext.traversal

import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuExtension

class Traversal : MenuExtension() {
    override val identifier = ".."
    override val author = "tests"
    override val version = "1.0"
    override fun register(context: ExtensionContext) = Unit
}
