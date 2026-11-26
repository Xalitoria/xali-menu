package fixture.ext.linkage

import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuExtension

/** What a hook that names a class of a plugin that is not installed does when it is first used. */
class LinkageExtension : MenuExtension() {
    override val identifier = "linkage"
    override val author = "tests"
    override val version = "1"

    override fun register(context: ExtensionContext) {
        throw NoClassDefFoundError("towny/object/Town")
    }
}
