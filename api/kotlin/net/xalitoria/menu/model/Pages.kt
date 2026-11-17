package net.xalitoria.menu.model

import kotlinx.serialization.Serializable
import net.xalitoria.menu.Catalog

/**
 * What makes a menu paged: the elements of [catalog] are drawn one page at a time into [slots], each as [item].
 *
 * The elements are sorted (see [sort]), then the ones for which the [item]'s `require` gate does not hold are left out, and
 * only then split into pages, so every page but the last is full. The gate and everything the item shows can use the
 * element's values as `{name}`. The item's own `slot` and `slots` are not used; the nth element of the page goes into the
 * nth of [slots], or of the cells of [layout]. An item of the menu in one of the slots is drawn when the page has no
 * element for it.
 *
 * A [layout] is rows of nine characters, the way the menu looks (`#########`, `#xxxxxxx#`): every [cell] character is a
 * slot for an element, read left to right and top to bottom, and the other characters are only there to draw by. A row
 * of fewer characters is taken as ending in blanks. Give [slots] or [layout], not both.
 */
@Serializable
data class Pages(
    /** The source of the elements. */
    val catalog: Catalog,
    /** The slots the elements go into. */
    val slots: List<Int> = [],
    /** The item drawn for each element. */
    val item: Item,
    /** Rows of nine characters that mark the slots of the elements instead of [slots]. */
    val layout: List<String> = [],
    /** The character of [layout] that stands for an element. */
    val cell: String = "x",
    /** The name of the element value to order by, such as `name`; numbers are ordered as numbers. The catalog's own order when absent. */
    val sort: String? = null,
    /** Whether the order of [sort] is reversed. */
    val descending: Boolean = false,
    /** Drawn in the first of the [slots] when the catalog has no element to list. */
    val empty: Item? = null,
) {
    init {
        require(slots.isEmpty() || layout.isEmpty()) { "pages take slots or a layout, not both" }
        require(cell.length == 1) { "pages.cell is one character, not '$cell'" }
        require(layout.all { it.length <= 9 }) { "a row of pages.layout has more than 9 characters" }
        require(slots.distinct().size == slots.size) { "a slot is listed twice in pages.slots" }
        require(listed().isNotEmpty()) { "pages need at least one slot: list them in slots, or mark cells in layout" }
    }

    /** The slots the elements go into, in order: [slots], or the [cell]s of [layout]. */
    fun listed(): List<Int> = if (layout.isEmpty()) slots else layout.flatMapIndexed { row, text ->
        text.mapIndexedNotNull { column, c -> if (c == cell[0]) row * 9 + column else null }
    }
}
