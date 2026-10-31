package mc.xalitoria.menu.api.model

import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Catalog

/**
 * What makes a menu paged: the elements of [catalog] are drawn one page at a time into [slots], each as [item].
 *
 * The elements are sorted (see [sort]), then the ones for which the [item]'s `view` gate does not hold are left out, and
 * only then split into pages, so every page but the last is full. The gate and everything the item shows can use the
 * element's values as `{name}`. The item's own `slot` and `slots` are not used; the nth element of the page goes into the
 * nth of [slots]. An item of the menu in one of the slots is drawn when the page has no element for it.
 */
@Serializable
data class Pages(
    val catalog: Catalog,
    val slots: List<Int>,
    val item: Item,
    /** The name of the element value to order by, such as `name`; numbers are ordered as numbers. The catalog's own order when absent. */
    val sort: String? = null,
    val descending: Boolean = false,
    /** Drawn in the first of the [slots] when the catalog has no element to list. */
    val empty: Item? = null,
) {
    init {
        require(slots.isNotEmpty()) { "pages need at least one slot" }
        require(slots.distinct().size == slots.size) { "a slot is listed twice in pages.slots" }
    }
}
