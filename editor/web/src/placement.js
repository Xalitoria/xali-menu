// Where an item is drawn, and changing that. A menu opens a window with two parts: the menu's own slots (the area `items`) and the player's
// rows under it (the area `inventory`). An item can be in both, and in any number of slots of each, so it is defined once and used where it is
// wanted. Each item belongs to one list (its section): those of `items` say their slots of the window with `slot` and `slots`, and those of the
// player's inventory with `inventory-slots`; those of `inventory` are only drawn in the player's inventory, with `slot` and `slots`.
// The functions work on the menu as plain data. Those that change it do so in place and return the menu, or null for no change, so they can be
// given to `Store.change`.

import { freeId } from './items.js'

/** The two parts of the window the editor draws: the menu's slots and the slots of the player's inventory. */
export const AREAS = ['items', 'inventory']

/** The key an item of [section] has the slots of [area] in, or null when an item of that list is never drawn there. */
export function claimKey(section, area) {
  if (section === 'items') return area === 'items' ? 'slot' : 'inventory-slots'
  return area === 'inventory' ? 'slot' : null
}

/** Whether an item of [section] can be drawn in [area] as it is (one of `inventory` can only be in the player's inventory until it is moved). */
export function canClaim(section, area) { return claimKey(section, area) !== null }

/** The slots [item] of [section] claims in [area] by what it says itself (not by the layout), in the order it says them. */
export function claimsOf(item, section, area) {
  const key = claimKey(section, area)
  if (key === null || !item) return []
  const list = key === 'slot' ? [item.slot, ...(item.slots ?? [])] : (item[key] ?? [])
  return list.filter((n, i) => typeof n === 'number' && list.indexOf(n) === i)
}

/** Sets the slots [item] of [section] claims in [area] to [slots], writing a lone slot as `slot` and the rest as `slots`. */
export function setClaims(item, section, area, slots) {
  const key = claimKey(section, area)
  if (key === null) return item
  if (key === 'slot') {
    delete item.slot
    delete item.slots
    if (slots.length) item.slot = slots[0]
    if (slots.length > 1) item.slots = slots.slice(1)
  } else if (slots.length) item[key] = slots
  else delete item[key]
  return item
}

/** Draws the item [id] of [section] in [slot] of [area] too. Null when it is there already or cannot be. */
export function addClaim(menu, id, section, area, slot) {
  const item = menu[section]?.[id]
  if (!item || !canClaim(section, area) || !Number.isInteger(slot) || slot < 0) return null
  const claimed = claimsOf(item, section, area)
  if (claimed.includes(slot)) return null
  setClaims(item, section, area, [...claimed, slot])
  return menu
}

/** Stops the item [id] of [section] being drawn in [slot] of [area]. Null when it was not (a slot the layout gives it is not its own to remove). */
export function removeClaim(menu, id, section, area, slot) {
  const item = menu[section]?.[id]
  const claimed = claimsOf(item, section, area)
  if (!claimed.includes(slot)) return null
  setClaims(item, section, area, claimed.filter((n) => n !== slot))
  return menu
}

/**
 * Takes the item [id] of [section] from [from] (`{area, slot}`) to [to], or, when [copy] is true, draws it in [to] as well and leaves it in [from].
 * Null when nothing changes.
 */
export function moveClaim(menu, id, section, from, to, copy = false) {
  if (from.area === to.area && from.slot === to.slot) return null
  const item = menu[section]?.[id]
  if (!item || !canClaim(section, to.area)) return null
  const claimed = claimsOf(item, section, to.area)
  const source = claimsOf(item, section, from.area)
  const leaves = !copy && source.includes(from.slot)
  // To a slot it is in already, the item only leaves the one it came from.
  if (claimed.includes(to.slot) && !(leaves && from.area !== to.area)) {
    return leaves && from.area === to.area ? removeClaim(menu, id, section, from.area, from.slot) : null
  }
  if (leaves) removeClaim(menu, id, section, from.area, from.slot)
  addClaim(menu, id, section, to.area, to.slot)
  return menu
}

/**
 * Moves the item [id] from the player's inventory list into `items`, so that it can be drawn in the window too: its slots become `inventory-slots`.
 * It keeps its id unless `items` has one like it. Returns the id it has now, or null for no such item.
 */
export function moveToItems(menu, id) {
  const item = menu.inventory?.[id]
  if (!item) return null
  const claimed = claimsOf(item, 'inventory', 'inventory')
  const made = id in (menu.items ?? {}) ? freeId(menu.items, `${id}-`) : id
  delete menu.inventory[id]
  if (Object.keys(menu.inventory).length === 0) delete menu.inventory
  setClaims(item, 'inventory', 'inventory', [])
  menu.items = { ...(menu.items ?? {}), [made]: item }
  setClaims(item, 'items', 'inventory', claimed)
  return made
}

/** Every place the item [id] of [section] is drawn: its own slots of each area, and the cells of the layout that stand for it (the window only). */
export function placesOf(menu, id, section = 'items') {
  const item = menu[section]?.[id]
  const layout = []
  if (section === 'items') {
    const legend = menu.legend ?? {}
    ;(menu.layout ?? []).forEach((row, r) => [...String(row)].forEach((c, column) => { if (legend[c] === id) layout.push(r * 9 + column) }))
  }
  return { items: claimsOf(item, section, 'items'), inventory: claimsOf(item, section, 'inventory'), layout }
}

/** For each slot of the player's inventory, the items drawn there as `{name, section}`, the one drawn first at the front: those of `inventory` and those of `items`. */
export function inventoryEntries(menu) {
  const slots = new Map()
  for (const section of ['inventory', 'items']) {
    for (const [name, item] of Object.entries(menu[section] ?? {})) {
      for (const slot of claimsOf(item, section, 'inventory')) {
        if (!slots.has(slot)) slots.set(slot, [])
        slots.get(slot).push({ name, section })
      }
    }
  }
  const priority = ({ name, section }) => menu[section][name].priority ?? 0
  for (const entries of slots.values()) entries.sort((a, b) => priority(a) - priority(b))
  return slots
}
