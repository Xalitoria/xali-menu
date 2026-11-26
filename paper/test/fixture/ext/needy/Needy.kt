package fixture.ext.needy

import fixture.shared.Seen
import net.xalitoria.menu.Action
import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.MenuExtension

class Go : Action {
    override fun run(ctx: MenuContext) = Unit
}

class NeedyExtension : MenuExtension() {
    override val identifier = "needy"
    override val author = "tests"
    override val version = "1"
    override val requiredPlugins = listOf("Needed")

    override fun register(context: ExtensionContext) {
        // The class is in another plugin's loader; the extension has no way to name it but by its name.
        Seen.depLoader = Class.forName("fixture.dep.Dep", false, javaClass.classLoader).classLoader
        context.registerAction("go") { Go() }
    }

    override fun unregister() {
        Seen.log += "needy unregistered"
    }
}
