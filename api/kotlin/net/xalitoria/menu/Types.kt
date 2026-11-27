package net.xalitoria.menu

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

/** The same for a [Catalog], registered with [XaliMenuApi.registerCatalog]: `{ type = "myplugin:homes" }` calls [create]. */
fun interface CatalogType {
    fun create(settings: Settings): Catalog
}

/**
 * How a plugin's own [ItemProperty] is built from a menu file, registered with [XaliMenuApi.registerItemProperty]:
 * `"myplugin:tag" { key = "quest", value = "intro" }` calls [create] with the keys of that object (or a single `value`
 * key when written as a scalar or list). Throw an [IllegalArgumentException] for a bad key; the menu is reported as
 * broken and not loaded.
 */
fun interface ItemPropertyType {
    fun create(settings: Settings): ItemProperty
}
