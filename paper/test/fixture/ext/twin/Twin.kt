package fixture.ext.twin

import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuExtension

class First : MenuExtension() {
    override val identifier = "first"
    override val author = "tests"
    override val version = "1"

    override fun register(context: ExtensionContext) = Unit
}

class Second : MenuExtension() {
    override val identifier = "second"
    override val author = "tests"
    override val version = "1"

    override fun register(context: ExtensionContext) = Unit
}
