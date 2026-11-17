// A paged menu (`pages`) as the editor shows it: which slots the elements go into, and the window as it looks on the first page, with
// the item template drawn for each slot from example values of the catalog. These are pure functions on the menu as plain data.

/** The ids of the items the preview adds: the template drawn for an element (a number follows), the template itself, and the item for no elements. */
export const ELEMENT = '@element'
export const TEMPLATE = '@pages.item'
export const EMPTY = '@pages.empty'

/** Whether [id] is one of the ids above: an item that the menu file does not have under that name. */
export const isPaged = (id) => typeof id === 'string' && id.startsWith('@')

/** The slots the elements go into, in order: `slots`, or the cell characters of `layout` (read left to right, top to bottom), as the plugin reads them. */
export function listedSlots(pages) {
  if (!pages) return []
  if (!(pages.layout?.length)) return [...(pages.slots ?? [])].filter((s) => Number.isInteger(s))
  const cell = pages.cell ?? 'x'
  return pages.layout.flatMap((row, r) => [...String(row)].flatMap((c, column) => (c === cell ? [r * 9 + column] : [])))
}

/** [pages] with [slot] added to the slots of the elements, or taken out if it is one; a layout has its character changed instead. */
export function toggleSlot(pages, slot, columns = 9) {
  const next = structuredClone(pages)
  if (next.layout?.length) {
    const cell = next.cell ?? 'x'
    const row = Math.floor(slot / columns)
    const column = slot % columns
    while (next.layout.length <= row) next.layout.push('')
    const chars = [...String(next.layout[row]).padEnd(column + 1, ' ')]
    chars[column] = chars[column] === cell ? ' ' : cell
    next.layout[row] = chars.join('').trimEnd()
    return next
  }
  const slots = new Set(next.slots ?? [])
  if (slots.has(slot)) slots.delete(slot); else slots.add(slot)
  next.slots = [...slots].sort((a, b) => a - b)
  return next
}

/** The slots between the border of a chest of [count] slots (the second row to the one before the last, without the first and last column); all of them when there is no border to leave. */
export function interiorSlots(count, columns = 9) {
  const rows = Math.ceil(count / columns)
  if (columns !== 9 || rows <= 2) return Array.from({ length: count }, (_, i) => i)
  const slots = []
  for (let row = 1; row < rows - 1; row++) for (let column = 1; column < columns - 1; column++) slots.push(row * columns + column)
  return slots
}

/** A paged menu's `pages` to start from: the players online, in the slots between the border, each drawn as a paper with its name. */
export function defaultPages(count, columns = 9) {
  return { catalog: { type: 'players' }, slots: interiorSlots(count, columns), item: { material: 'paper', name: '&e{name}' } }
}

/** The example value for the [n]th element (from 1): numbers count on from the example, text gets the number at its end after the first. */
export function sampleValue(example, n) {
  const text = String(example ?? '')
  if (text !== '' && /^-?\d+$/.test(text)) return String(Number(text) + n - 1)
  return n === 1 ? text : `${text}${n}`
}

/** [count] example elements for a catalog whose values are [fields] (name to example); each has `index` too. */
export function sampleElements(fields, count) {
  return Array.from({ length: count }, (_, i) => ({
    ...Object.fromEntries(Object.entries(fields ?? {}).map(([name, example]) => [name, sampleValue(example, i + 1)])),
    index: String(i + 1),
  }))
}

/** [text] with `{name}` replaced for each of [values]; a name that is not one of them stays as it is. */
export function fillText(text, values) {
  return text.replace(/\{([^{}\s]+)\}/g, (whole, name) => (Object.hasOwn(values, name) ? values[name] : whole))
}

/** A copy of [value] (an item, or any part of one) with every text filled in from [values]. */
export function fillAll(value, values) {
  if (typeof value === 'string') return fillText(value, values)
  if (Array.isArray(value)) return value.map((v) => fillAll(v, values))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, fillAll(v, values)]))
  return value
}

/** The pages the preview pretends the catalog fills: the first full, the second half full, so the items under the elements show through. */
export const PREVIEW_PAGES = 2

/** How many example elements the preview pretends there are for [perPage] slots: a full page and half of one. */
export const previewTotal = (perPage) => perPage + Math.ceil(perPage / 2)

/** How many pages the preview has for [perPage] slots: those of the real [elements] when there are some, otherwise the example pages. */
export const previewPages = (perPage, elements = null) => (elements?.length ? Math.ceil(elements.length / perPage) : PREVIEW_PAGES)

/**
 * The menu as the window shows it: for a paged menu, one [page] (from 1) of example elements, with the template drawn in each slot that has
 * one. The template is put in front of the items that claim the same slots; its `require` gate is left out, since the editor does not know what
 * the gate decides. With [empty], the page is the one of a catalog with nothing to list: no element, and `pages.empty` (if there is one) in
 * the first slot, as the plugin draws it. A menu that is not paged is returned as it is. [fields] is what the catalog's elements hold (name
 * to example), and [elements] are the real ones from the server when there are some, each with its `index`; without them `{page}`,
 * `{pages}` and `{total}` are filled as the plugin would for [previewTotal] example elements.
 */
export function previewMenu(menu, fields, { page = 1, empty = false, elements = null } = {}) {
  const pages = menu.pages
  const listed = listedSlots(pages)
  if (!pages?.item || listed.length === 0) return menu
  const perPage = listed.length
  const all = empty ? [] : elements?.length ? elements : sampleElements(fields, previewTotal(perPage))
  const total = all.length
  const count = Math.max(1, Math.ceil(total / perPage))
  const shown = Math.min(Math.max(1, page), count)
  const tokens = { page: String(shown), pages: String(count), total: String(total), page_next: String(Math.min(shown + 1, count)), page_prev: String(Math.max(shown - 1, 1)) }
  const onPage = all.slice((shown - 1) * perPage, shown * perPage)
  const items = Object.fromEntries(Object.entries(menu.items ?? {}).map(([id, item]) => [id, fillAll(item, tokens)]))
  onPage.forEach((element, i) => {
    const { require, slots, ...template } = fillAll(pages.item, { ...tokens, ...element })
    items[`${ELEMENT}${i + 1}`] = { ...template, slot: listed[i], priority: -1_000_000 }
  })
  if (empty && pages.empty) {
    const { require, slots, ...item } = fillAll(pages.empty, tokens)
    items[EMPTY] = { ...item, slot: listed[0], priority: -1_000_000 }
  }
  return { ...menu, title: fillText(menu.title ?? '', tokens), items }
}
