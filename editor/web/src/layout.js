// The menu matrix as a grid of characters, and what painting one does: pure functions on the rows as the file has them (`cells = ["#########", "#  a  b #"]`),
// read the way the plugin reads them: row by row, nine characters to a row, a space or a character `matrix.map` does not know being nothing.

/** The columns of a row of the layout: the plugin reads nine to a row. */
export const COLUMNS = 9

/** The characters of the rows as a grid of [rows] rows, each [COLUMNS] long, with a space where a row is short or missing. */
export function grid(layout, rows) {
  return Array.from({ length: rows }, (_, r) => [...String(layout?.[r] ?? '').padEnd(COLUMNS, ' ')].slice(0, COLUMNS))
}

/** A grid back as the rows of the file: a row loses its blanks at the end, and the rows after the last that has a character are not kept. */
export function rowsOf(cells) {
  const rows = cells.map((row) => row.join('').trimEnd())
  while (rows.length > 0 && rows.at(-1) === '') rows.pop()
  return rows
}

/** [layout] with the cell at [row] and [column] set to [char] (a space is nothing). A character longer than one is cut to its first. */
export function paint(layout, rows, row, column, char) {
  const cells = grid(layout, Math.max(rows, row + 1))
  cells[row][column] = [...String(char ?? ' ')][0] ?? ' '
  return rowsOf(cells)
}

/** How many cells of the layout each character stands for, in the order the characters first appear. */
export function counts(layout) {
  const out = new Map()
  for (const row of layout ?? []) for (const c of String(row)) if (c !== ' ') out.set(c, (out.get(c) ?? 0) + 1)
  return out
}

/** The characters of `cells` that `map` does not know (they are nothing in the menu), and the characters of `map` that stand nowhere. */
export function strays(cells, map) {
  const used = counts(cells)
  return { unknown: [...used.keys()].filter((c) => !(c in (map ?? {}))), unused: Object.keys(map ?? {}).filter((c) => !used.has(c)) }
}
