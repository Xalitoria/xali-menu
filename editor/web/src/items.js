// What the editor does to the items of a menu that is more than setting a key: giving an item another id, finding an id and a slot that are free.
// The functions work on the menu as plain data. Those that change it do so in place and return the menu, or null for no change, so they can be
// given to `Store.change`.

/** The id of an item: its key in `items`. It is used to refer to the item from the menu matrix map and in messages. */
export const ID_PATTERN = /^[^\s{}%]+$/

/** The first of `item1`, `item2`... that is not an id of [items]. */
export function freeId(items, base = 'item') {
  let n = 1
  while (`${base}${n}` in items) n++
  return `${base}${n}`
}

/** Why [id] cannot be the id of an item of [menu] other than [own], or null if it can. */
export function idProblem(menu, id, own = null) {
  if (!id) return 'The id cannot be empty.'
  if (!ID_PATTERN.test(id)) return 'The id cannot have spaces, braces or %.'
  if (id !== own && id in (menu.items ?? {})) return `Another item is called ${id}.`
  return null
}

/** Gives the item [from] the id [to], keeping its place in the list and the matrix map characters that stand for it. Null when nothing changes. */
export function renameItem(menu, from, to) {
  if (from === to || idProblem(menu, to, from) || !(from in (menu.items ?? {}))) return null
  menu.items = Object.fromEntries(Object.entries(menu.items).map(([id, item]) => [id === from ? to : id, item]))
  if (menu.matrix?.map) for (const [character, id] of Object.entries(menu.matrix.map)) if (id === from) menu.matrix.map[character] = to
  return menu
}

/** Removes the item [id] and the matrix map characters that stood for it. Null when there is no such item. */
export function removeItem(menu, id) {
  if (!(id in (menu.items ?? {}))) return null
  delete menu.items[id]
  if (menu.matrix?.map) {
    for (const [character, name] of Object.entries(menu.matrix.map)) if (name === id) delete menu.matrix.map[character]
  }
  return menu
}

/** Puts a copy of the item [id] under the id [made] right after it. It takes [slot] if it had a slot of its own (none when [slot] is null), and no `slots` or `inventory-slots`. */
export function duplicateItem(menu, id, made, slot = null) {
  if (!(id in (menu.items ?? {})) || made in menu.items) return null
  const copy = structuredClone(menu.items[id])
  delete copy.slots
  delete copy['inventory-slots']
  if (typeof copy.slot === 'number') { if (slot === null) delete copy.slot; else copy.slot = slot }
  menu.items = Object.fromEntries(Object.entries(menu.items).flatMap(([name, item]) => (name === id ? [[name, item], [made, copy]] : [[name, item]])))
  return menu
}

/** The lowest slot below [count] that no item claims (given as a set of slots), or null when the window is full. */
export function firstFreeSlot(claimed, count) {
  for (let slot = 0; slot < count; slot++) if (!claimed.has(slot)) return slot
  return null
}

/** Prefixes of a material that are not a custom item: heads and skins are drawn by the editor itself. */
const BUILT_IN = new Set(['head', 'texture', 'basehead', 'minecraft'])

/** Whether [material] names an item of a plugin (`nexo:ruby`, `itemsadder:ruby`), which a resource pack usually supplies the picture of. */
export function isCustomMaterial(material) {
  const text = String(material ?? '').trim()
  const colon = text.indexOf(':')
  return colon > 0 && !text.includes('%') && !BUILT_IN.has(text.slice(0, colon).toLowerCase())
}
