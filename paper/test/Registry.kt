import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.format.MenuFormat

/** The built-in types, as the plugin registers them, for tests that read or write menus. */
object Registry {
    val registry = TypeRegistry().also {
        Actions.register(it)
        Conditions.register(it)
    }

    val format = MenuFormat(registry.module)
}
