package net.xalitoria.menu.model

import kotlinx.serialization.Serializable

/**
 * A drawing of a menu's slots as rows of characters, with the item each character stands for written next to it, as in
 * AbstractMenus' `matrix { cells, templates }`. It is used by [Pages.matrix], where the elements of a catalog fill the cells.
 *
 * [cells] are rows of at most nine characters, read left to right and top to bottom, so the nth character of a row is the
 * nth column of the window. [templates] name the characters that stand for something: a key is one character, and its
 * value is the item drawn in every cell where that character stands. A character with no template is a blank, and a row of
 * fewer characters ends in blanks. A character that HOCON reads as something else needs quotes (`"#"`).
 *
 * ```hocon
 * cells = ["#########", "#xxxxxxx#", "#xxxxxxx#", "####c####"]
 * templates {
 *     x { material = "head:{name}", name = "&e{name}" }
 *     c { material = "barrier", name = "&cClose" }
 * }
 * ```
 */
@Serializable
data class Matrix(
    /** The rows of the drawing, at most nine characters each. */
    val cells: List<String>,
    /** The item of each character that stands for something, by that character. */
    val templates: Map<String, Item>,
) {
    init {
        require(templates.isNotEmpty()) { "a matrix needs at least one template" }
        templates.keys.firstOrNull { it.length != 1 }?.let { throw IllegalArgumentException("a template of a matrix is named by one character, not '$it'") }
        require(cells.all { it.length <= 9 }) { "a row of matrix.cells has more than 9 characters" }
        require(cells().isNotEmpty()) { "no character of matrix.cells has a template, so the matrix has no cell" }
    }

    /** The template of each cell by its slot, in the order the cells are read. */
    fun cells(): Map<Int, Item> {
        val found = LinkedHashMap<Int, Item>()
        cells.forEachIndexed { row, text ->
            text.forEachIndexed { column, c -> templates[c.toString()]?.let { found[row * 9 + column] = it } }
        }
        return found
    }
}
