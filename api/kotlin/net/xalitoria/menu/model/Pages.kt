package net.xalitoria.menu.model

import kotlinx.serialization.Serializable
import net.xalitoria.menu.Catalog

/**
 * What makes a menu paged: the elements of [catalog] are drawn one page at a time into the slots of the menu.
 *
 * The elements are sorted (see [sort]), then the ones for which the [item]'s `view` gate does not hold are left out, and
 * only then split into pages, so every page but the last is full. The gate and everything the item shows can use the
 * element's values as `{name}`. The item's own `slot` and `slots` are not used; the nth element of the page goes into the
 * nth of the slots. An item of the menu in one of the slots is drawn when the page has no element for it.
 *
 * The slots come from one of three places, and only one may be given:
 *
 * - [slots], a list of slot numbers, drawn with [item];
 * - a [layout], rows of nine characters, the way the menu looks (`#########`, `#xxxxxxx#`): every [cell] character is a
 *   slot for an element, read left to right and top to bottom, and the other characters are only there to draw by. A row
 *   of fewer characters is taken as ending in blanks. It is drawn with [item];
 * - a [matrix], which names an item for each of several characters, so the cells of one drawing can show different things.
 *   The elements still fill the cells in order, and each is drawn as the template of the cell it falls in. [item] is not used.
 *
 * With a [matrix] there is no single gate to leave elements out by before they are split into pages. The `view` of a
 * template is asked after the element has its cell, and an element whose template does not show leaves its cell to what the
 * menu has there.
 */
@Serializable
data class Pages(
    /** The source of the elements. */
    val catalog: Catalog,
    /** The slots the elements go into. */
    val slots: List<Int> = [],
    /** The item drawn for each element when the slots come from [slots] or [layout]. */
    val item: Item? = null,
    /** Rows of nine characters that mark the slots of the elements instead of [slots]. */
    val layout: List<String> = [],
    /** The character of [layout] that stands for an element. */
    val cell: String = "x",
    /** The name of the element value to order by, such as `name`; numbers are ordered as numbers. The catalog's own order when absent. */
    val sort: String? = null,
    /** Whether the order of [sort] is reversed. */
    val descending: Boolean = false,
    /** Drawn in the first of the slots when the catalog has no element to list. */
    val empty: Item? = null,
    /** Rows of characters and the item of each, instead of [slots] or [layout] with one [item]. */
    val matrix: Matrix? = null,
) {
    init {
        require(slots.isEmpty() || layout.isEmpty()) { "pages take slots or a layout, not both" }
        require(matrix == null || (slots.isEmpty() && layout.isEmpty())) { "pages take a matrix, or slots, or a layout, only one of them" }
        require(matrix == null || item == null) { "pages with a matrix take their items from matrix.templates, not from item" }
        require(matrix != null || item != null) { "pages need an item for each element, or a matrix with templates" }
        require(cell.length == 1) { "pages.cell is one character, not '$cell'" }
        require(layout.all { it.length <= 9 }) { "a row of pages.layout has more than 9 characters" }
        require(slots.distinct().size == slots.size) { "a slot is listed twice in pages.slots" }
        require(listed().isNotEmpty()) { "pages need at least one slot: list them in slots, or mark cells in layout or matrix" }
    }

    /** The slots the elements go into, in order: the cells of [matrix], [slots], or the [cell]s of [layout]. */
    fun listed(): List<Int> = when {
        matrix != null -> matrix.cells().keys.toList()
        layout.isEmpty() -> slots
        else -> layout.flatMapIndexed { row, text ->
            text.mapIndexedNotNull { column, c -> if (c == cell[0]) row * 9 + column else null }
        }
    }

    /** The item drawn for the element that falls in each of the [listed] slots. */
    fun templates(): Map<Int, Item> = matrix?.cells() ?: listed().associateWith { item!! }
}
