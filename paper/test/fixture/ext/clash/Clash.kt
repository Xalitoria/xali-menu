package fixture.ext.clash

import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuExtension

/** Says it is `plain`, as the extension of the first jar does. */
class ClashExtension : MenuExtension() {
    override val identifier = "plain"
    override val author = "tests"
    override val version = "9"

    override fun register(context: ExtensionContext) = Unit
}
