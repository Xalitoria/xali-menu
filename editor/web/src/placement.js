// Where an item is drawn, and changing that. A menu opens a window with two parts: the menu's own slots (the area `items`) and the player's
// rows under it (the area `inventory`). An item is defined once, in `items`, and can be in any number of slots of each part: `slot` and `slots` are
// its slots of the menu, `inventory-slots` those of the player's inventory.
// The functions work on the menu as plain data. Those that change it do so in place and return the menu, or null for no change, so they can be
// given to `Store.change`.

/** The two parts of the window the editor draws: the menu's slots and the slots of the player's inventory. */
export const AREAS = ['items', 'inventory']

/** The slots a matrix claims (an item's `matrix`): every character of its rows but `-` and a space, nine to a row, left to right and top to bottom. */
export function matrixSlots(rows) {
  return (Array.isArray(rows) ? rows : [rows]).flatMap((row, r) => [...String(row ?? '')].flatMap((c, column) => (c !== '-' && c !== ' ' ? [r * 9 + column] : [])))
}

/** The slots [item] claims in [area] by what it says itself (not by the layout), in the order it says them. A `matrix` counts as its slots. */
export function claimsOf(item, area) {
  if (!item) return []
  const list = area === 'items' ? [item.slot, ...(item.slots ?? []), ...(item.matrix ? matrixSlots(item.matrix) : [])] : (item['inventory-slots'] ?? [])
  return list.filter((n, i) => typeof n === 'number' && list.indexOf(n) === i)
}

/** Sets the slots [item] claims in [area] to [slots], writing a lone slot of the menu as `slot` and the rest as `slots`. A `matrix` is written out as slots, so it is not claimed twice. */
export function setClaims(item, area, slots) {
  if (area === 'items') {
    delete item.slot
    delete item.slots
    delete item.matrix
    if (slots.length) item.slot = slots[0]
    if (slots.length > 1) item.slots = slots.slice(1)
  } else if (slots.length) item['inventory-slots'] = slots
  else delete item['inventory-slots']
  return item
}

/** Draws the item [id] in [slot] of [area] too. Null when it is there already or there is no such item or slot. */
export function addClaim(menu, id, area, slot) {
  const item = menu.items?.[id]
  if (!item || !Number.isInteger(slot) || slot < 0) return null
  const claimed = claimsOf(item, area)
  if (claimed.includes(slot)) return null
  setClaims(item, area, [...claimed, slot])
  return menu
}

/** Stops the item [id] being drawn in [slot] of [area]. Null when it was not (a slot the layout gives it is not its own to remove). */
export function removeClaim(menu, id, area, slot) {
  const item = menu.items?.[id]
  const claimed = claimsOf(item, area)
  if (!claimed.includes(slot)) return null
  setClaims(item, area, claimed.filter((n) => n !== slot))
  return menu
}

/**
 * Takes the item [id] from [from] (`{area, slot}`) to [to], or, when [copy] is true, draws it in [to] as well and leaves it in [from].
 * Null when nothing changes.
 */
export function moveClaim(menu, id, from, to, copy = false) {
  if (from.area === to.area && from.slot === to.slot) return null
  const item = menu.items?.[id]
  if (!item) return null
  const leaves = !copy && claimsOf(item, from.area).includes(from.slot)
  // To a slot it is in already, the item only leaves the one it came from.
  if (claimsOf(item, to.area).includes(to.slot)) return leaves ? removeClaim(menu, id, from.area, from.slot) : null
  if (leaves) removeClaim(menu, id, from.area, from.slot)
  addClaim(menu, id, to.area, to.slot)
  return menu
}

/** Every place the item [id] is drawn: its own slots of each area, and the cells of the layout that stand for it (the menu only). */
export function placesOf(menu, id) {
  const layout = []
  const legend = menu.legend ?? {}
  ;(menu.layout ?? []).forEach((row, r) => [...String(row)].forEach((c, column) => { if (legend[c] === id) layout.push(r * 9 + column) }))
  const item = menu.items?.[id]
  return { items: claimsOf(item, 'items'), inventory: claimsOf(item, 'inventory'), layout }
}

/** For each slot of the player's inventory, the ids of the items drawn there, the one drawn first at the front. */
export function inventoryEntries(menu) {
  const slots = new Map()
  for (const [name, item] of Object.entries(menu.items ?? {})) {
    for (const slot of claimsOf(item, 'inventory')) {
      if (!slots.has(slot)) slots.set(slot, [])
      slots.get(slot).push(name)
    }
  }
  const priority = (name) => menu.items[name].priority ?? 0
  for (const names of slots.values()) names.sort((a, b) => priority(a) - priority(b))
  return slots
}
