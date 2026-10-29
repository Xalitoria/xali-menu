package mc.xalitoria.menu.api

/**
 * How a plugin's own action is built from a menu file, registered with [XaliMenuApi.registerAction]: `{ type = "myplugin:reward",
 * points = 3 }` calls [create] with the keys next to `type`. Throw an [IllegalArgumentException] for a bad key; the menu
 * is reported as broken and not loaded.
 */
fun interface ActionType {
    fun create(settings: Settings): Action
}

/** The same for a [Condition], registered with [XaliMenuApi.registerCondition]. */
fun interface ConditionType {
    fun create(settings: Settings): Condition
}
