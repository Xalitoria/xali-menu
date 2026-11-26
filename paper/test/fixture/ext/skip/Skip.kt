package fixture.ext.skip

import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuExtension

class SkippedExtension : MenuExtension() {
    override val identifier = "skipped"
    override val author = "tests"
    override val version = "1"

    override fun canRegister() = false

    override fun register(context: ExtensionContext) = error("never called")
}
