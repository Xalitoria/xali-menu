package mc.xalitoria.menu.paper.runtime

/** Which page is shown, of how many, for [total] elements listed [perPage] at a time. Pages are counted from 0 here. */
data class PageState(val total: Int, val perPage: Int, val requested: Int) {
    /** There is always one page, even if it is empty. */
    val pages: Int = maxOf(1, (total + perPage - 1) / perPage)

    /** [requested], or the nearest page there is: the list may have got shorter since it was asked for. */
    val page: Int = requested.coerceIn(0, pages - 1)

    /** The elements of the page are those from [from] on, as many as fit. */
    val from: Int get() = page * perPage

    /** The values of the page for text: pages are numbered from 1 there. */
    fun values(): Map<String, String> = mapOf(
        "page" to (page + 1).toString(),
        "pages" to pages.toString(),
        "total" to total.toString(),
        "page_next" to (minOf(page + 1, pages - 1) + 1).toString(),
        "page_prev" to (maxOf(page - 1, 0) + 1).toString(),
    )
}

/**
 * [elements] ordered by the value called [field]: as numbers where both are numbers, otherwise as text without regard to
 * case, and an element without the value after those that have it. Elements that compare equal keep their order.
 */
fun sortElements(elements: List<Map<String, String>>, field: String?, descending: Boolean): List<Map<String, String>> {
    if (field == null) return elements
    val order = Comparator<Map<String, String>> { a, b ->
        val x = a[field]
        val y = b[field]
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> {
                val m = x.toBigDecimalOrNull()
                val n = y.toBigDecimalOrNull()
                if (m != null && n != null) m.compareTo(n) else x.compareTo(y, ignoreCase = true)
            }
        }
    }
    // A missing value stays last whichever way the rest is turned.
    val present = elements.filter { field in it }.sortedWith(if (descending) order.reversed() else order)
    return present + elements.filter { field !in it }
}

/** Gives each element its place in the list, from 1, as `index`. */
fun numbered(elements: List<Map<String, String>>): List<Map<String, String>> =
    elements.mapIndexed { i, element -> element + ("index" to (i + 1).toString()) }

/** The elements of one page: the nth goes into the nth slot, and [state] says which page it is. */
class PageLayout(val state: PageState, val placed: List<Pair<Int, Map<String, String>>>, val empty: Boolean)

/**
 * Lays [listed] out over the [slots] of a paged menu: sorted, then the ones [listable] turns away are dropped, then numbered,
 * and only then cut into pages, so every page but the last is full. [requested] is the page asked for, from 0.
 */
fun layoutPage(
    listed: List<Map<String, String>>,
    slots: List<Int>,
    sort: String?,
    descending: Boolean,
    requested: Int,
    listable: (Map<String, String>) -> Boolean,
): PageLayout {
    val elements = numbered(sortElements(listed, sort, descending).filter(listable))
    val state = PageState(elements.size, slots.size, requested)
    return PageLayout(state, slots.zip(elements.drop(state.from)), elements.isEmpty())
}
