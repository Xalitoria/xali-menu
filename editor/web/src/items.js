// What the editor does to the items of a menu that is more than setting a key: giving an item another id, finding an id and a slot that are free.
// The functions work on the menu as plain data. Those that change it do so in place and return the menu, or null for no change, so they can be
// given to `Store.change`.

/** The id of an item: its key in `items`. It is only used to refer to the item from the legend and in messages. */
export const ID_PATTERN = /^[^\s{}%]+$/

/** The first of `item1`, `item2`... that is not an id of [items]. */
export function freeId(items, base = 'item') {
  let n = 1
  while (`${base}${n}` in items) n++
  return `${base}${n}`
}

/**
 * The two lists of items a menu has: `items` in the window, and `inventory` in the player's own inventory, which the menu draws under it. Their
 * ids are separate (the legend only names the first), and each function below takes the list it works on, `items` unless told otherwise.
 */
export const SECTIONS = ['items', 'inventory']

/** Why [id] cannot be the id of an item of [section] of [menu] other than [own], or null if it can. */
export function idProblem(menu, id, own = null, section = 'items') {
  if (!id) return 'The id cannot be empty.'
  if (!ID_PATTERN.test(id)) return 'The id cannot have spaces, braces or %.'
  if (id !== own && id in (menu[section] ?? {})) return `Another item is called ${id}.`
  return null
}

/** Gives the item [from] the id [to], keeping its place in the list and the legend characters that stand for it. Null when nothing changes. */
export function renameItem(menu, from, to, section = 'items') {
  if (from === to || idProblem(menu, to, from, section) || !(from in (menu[section] ?? {}))) return null
  menu[section] = Object.fromEntries(Object.entries(menu[section]).map(([id, item]) => [id === from ? to : id, item]))
  if (section === 'items' && menu.legend) for (const [character, id] of Object.entries(menu.legend)) if (id === from) menu.legend[character] = to
  return menu
}

/** Removes the item [id] and the legend characters that stood for it. Null when there is no such item. */
export function removeItem(menu, id, section = 'items') {
  if (!(id in (menu[section] ?? {}))) return null
  delete menu[section][id]
  if (section === 'inventory' && Object.keys(menu.inventory).length === 0) delete menu.inventory
  if (section === 'items' && menu.legend) for (const [character, name] of Object.entries(menu.legend)) if (name === id) delete menu.legend[character]
  return menu
}

/** Puts a copy of the item [id] under the id [made] right after it. It takes [slot] if it had a slot of its own (none when [slot] is null), and no `slots`. */
export function duplicateItem(menu, id, made, slot = null, section = 'items') {
  if (!(id in (menu[section] ?? {})) || made in menu[section]) return null
  const copy = structuredClone(menu[section][id])
  delete copy.slots
  if (typeof copy.slot === 'number') { if (slot === null) delete copy.slot; else copy.slot = slot }
  menu[section] = Object.fromEntries(Object.entries(menu[section]).flatMap(([name, item]) => (name === id ? [[name, item], [made, copy]] : [[name, item]])))
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
