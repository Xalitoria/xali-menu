// The window of a menu as a grid of slots, with the items where they are drawn. Clicking a slot picks it, dragging an item to
// another slot moves it. What the player sees when several items claim a slot is the one with the lowest priority.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon, settings } from './icons.js'
import { geometry, playerSlot, inventoryIndex, INVENTORY_SLOTS } from './window.js'
import { viewOf, fitBox } from './scale.js'
import { decorations } from './decorations.js'
import { tooltipLines, drawTooltip } from './tooltip.js'
import { services } from './services.js'
import { isPaged } from './pages.js'
import { claimsOf, inventoryEntries } from './placement.js'

/** The slots of the menu each item claims: its `slot` and `slots`, and the cells of the layout that its legend character stands for. */
export function placements(menu) {
  const result = new Map()
  const claim = (name, slot) => { if (!result.has(name)) result.set(name, new Set()); result.get(name).add(slot) }
  for (const [name, item] of Object.entries(menu.items ?? {})) {
    if (typeof item.slot === 'number') claim(name, item.slot)
    for (const slot of item.slots ?? []) if (typeof slot === 'number') claim(name, slot)
  }
  const legend = menu.legend ?? {}
  ;(menu.layout ?? []).forEach((row, r) => [...String(row)].forEach((c, column) => { if (legend[c] && menu.items?.[legend[c]]) claim(legend[c], r * 9 + column) }))
  return result
}

/** For each slot, the names of the items that claim it, the one that is drawn first. */
export function bySlot(menu) {
  const slots = new Map()
  for (const [name, claimed] of placements(menu)) {
    for (const slot of claimed) {
      if (!slots.has(slot)) slots.set(slot, [])
      slots.get(slot).push(name)
    }
  }
  const priority = (name) => menu.items[name].priority ?? 0
  for (const names of slots.values()) names.sort((a, b) => priority(a) - priority(b))
  return slots
}

/** The room left around the window, in pixels of the game: at least this much, at most that much. */
const MIN_ROOM = 8
const MAX_ROOM = 256

export class Inventory {
  /**
   * @param {import('./store.js').Store} store
   * @param {{select: (slot: number, names: string[], area: string) => void,
   *   drop: (source: object, target: {area: string, slot: number}, copy: boolean) => void}} events
   */
  constructor(store, events) {
    this.store = store
    this.events = events
    this.slot = null
    /** The part of the window the picked slot is in: `items` for the menu's slots, `inventory` for the player's inventory under it. */
    this.area = 'items'
    this.root = h('div', { class: 'inventory' })
    this.tip = null
  }

  /**
   * The view to draw at: the GUI scale chosen, or the biggest at which the window, with a little room around it, fits in [box] (page pixels).
   * The room around the window is what is left of [box]: a glyph that reaches past the window is drawn there.
   */
  viewFor(g, box) {
    const ratio = window.devicePixelRatio || 1
    return viewOf(services.scale || fitBox(g.width + 2 * MIN_ROOM, g.height + 2 * MIN_ROOM, box.width, box.height, ratio), ratio)
  }

  /**
   * The window as the game draws it, with the slots of the menu and the player's inventory over it, in the middle of the space [box] (page
   * pixels, as wide and as high as the stage it is shown in); the items are drawn in as their models arrive. The player's inventory is below
   * the menu in the game: the items with `inventory-slots` are in its slots, and the others are plain. An item that claims a slot past the
   * menu's own with `slot` is shown there marked, since the menu does not draw there.
   * [menu] is what is drawn (the menu of the store unless the page shows it differently, as a paged menu with its first page); [listed] is the set of
   * slots the elements of a paged menu go into, which are marked; [painting] says that a click picks slots for them and does not pick an item.
   */
  draw(box = { width: 1000, height: 700 }, { menu = this.store.value, listed = new Set(), painting = false } = {}) {
    const count = slotCount(menu)
    const g = geometry(menu)
    const view = this.viewFor(g, box)
    const spare = (available, size) => Math.min(MAX_ROOM, Math.max(MIN_ROOM, Math.floor(((available * view.ratio) / view.device - size) / 2) - 1))
    const room = { x: spare(box.width, g.width), y: spare(box.height, g.height) }
    const cell = view.css(18)
    const slots = bySlot(menu)
    const inventorySlots = inventoryEntries(menu)
    // The coordinates of the game are those of the item, which lies one pixel inside the slot's border: the square of the slot starts one pixel up and to the left.
    const at = (x, y) => `left:${view.css(room.x + x - 1)}px;top:${view.css(room.y + y - 1)}px;width:${cell}px;height:${cell}px`
    /** A slot of [area], with the items [names] drawn in it, the one drawn first at the front. */
    const cellFor = (slot, names, place, extra = {}, area = 'items') => {
      const shown = names[0]
      const item = shown ? menu.items?.[shown] : null
      // What can be dragged is a slot the item says itself: not one of the layout, which is the layout's to move, and not an element of a paged menu.
      const own = Boolean(item) && !isPaged(shown) && !extra.outside && claimsOf(item, area).includes(slot)
      return h('div', {
        class: `slot ${this.slot === slot && this.area === area ? 'picked' : ''} ${names.length > 1 ? 'shared' : ''} ${area === 'items' && listed.has(slot) ? 'listed' : ''} ${extra.class ?? ''}`,
        'data-slot': slot, 'data-area': area, style: place,
        draggable: own,
        onclick: () => { this.slot = slot; this.area = area; this.events.select(slot, names, area) },
        ondragstart: (e) => {
          e.dataTransfer.effectAllowed = 'copyMove'
          e.dataTransfer.setData('text/plain', JSON.stringify({ kind: 'slot', name: shown, area, slot }))
        },
        ondragover: (e) => { e.preventDefault(); e.dataTransfer.dropEffect = e.altKey || e.ctrlKey ? 'copy' : e.dataTransfer.effectAllowed === 'copy' ? 'copy' : 'move' },
        ondrop: (e) => {
          e.preventDefault()
          // An item from a slot moves (with Alt or Ctrl held it is drawn in the new slot too); one from the list is drawn in the slot as well.
          let source
          try { source = JSON.parse(e.dataTransfer.getData('text/plain')) } catch { return }
          if (!source?.name || isPaged(source.name) || !menu.items?.[source.name]) return
          this.events.drop(source, { area, slot }, source.kind === 'item' || e.altKey || e.ctrlKey)
        },
        onmouseenter: (e) => this.showTip(item, shown, names, slot, e, view, extra.note),
        onmousemove: (e) => this.moveTip(e),
        onmouseleave: () => this.hideTip(),
      }, item && icon(item, view.device, view.ratio), item && decorations(item, services.text, settings.resolved.get(item.material)?.maxDamage ?? 0, view.device, view.ratio), h('span', { class: 'number' }, slot))
    }
    const grid = h('div', { class: 'slots layered' })
    for (let slot = 0; slot < count; slot++) {
      grid.append(cellFor(slot, slots.get(slot) ?? [], at(g.x + (slot % g.columns) * 18, g.y + Math.floor(slot / g.columns) * 18)))
    }
    // The player's inventory, slot 0 to 8 the hotbar and 9 to 35 the rows above it. An item of the window that has a slot past the menu's own (count to
    // count + 35, the rows first) is shown here too, marked as one that is never drawn.
    for (let slot = 0; slot < INVENTORY_SLOTS; slot++) {
      const index = inventoryIndex(slot)
      const names = inventorySlots.get(slot) ?? []
      const place = playerSlot(g, index)
      const outside = slots.get(count + index) ?? []
      if (names.length === 0 && outside.length) {
        grid.append(cellFor(count + index, outside, at(place.x, place.y), {
          class: 'outside', outside: true, note: "This slot is in the player's inventory. The items of the menu are not drawn there (only `inventory-slots` are), so this item is never shown there.",
        }))
      } else {
        grid.append(cellFor(slot, names, at(place.x, place.y), {
          class: names.length ? '' : 'plain', note: names.length ? "This item is drawn in the player's own inventory, over what the player has there." : "The player's own inventory: an item drawn here covers what the player has in this slot.",
        }, 'inventory'))
      }
    }
    // The window labels the player's rows `Inventory`; what they are for is said beside it, since nothing else in the window does.
    grid.append(h('div', { class: 'inv-caption', style: `left:${view.css(room.x + 64)}px;top:${view.css(room.y + g.inventoryLabel)}px;font-size:${Math.max(10, view.css(6))}px;line-height:${view.css(9)}px` },
      'click a slot to draw an item in it'))
    const background = services.window.draw(menu, { scale: view.device, ratio: view.ratio, margin: room })
    const width = view.css(g.width + 2 * room.x)
    const height = view.css(g.height + 2 * room.y)
    this.root.className = `inventory ${painting ? 'painting' : ''}`
    fill(this.root, h('div', { class: 'window layered-window', style: `width:${width}px;height:${height}px` }, background, grid))
  }

  /** The tooltip of the item the pointer is over, next to the pointer; an empty slot says which slot it is, and [note] adds a line. */
  async showTip(item, name, names, slot, event, view, note) {
    this.hideTip()
    const tip = h('div', { class: 'tooltip' })
    this.tip = tip
    document.body.append(tip)
    this.moveTip(event)
    try {
      const lang = (await services.assets.json('minecraft', 'lang', 'en_us')) ?? {}
      const lines = item ? tooltipLines(item, lang, services.renderer.knownName?.(item)) : [{ text: `Slot ${slot}`, color: '#aaaaaa' }]
      if (item && names.length > 1) lines.push({ text: `Also claimed by ${names.filter((n) => n !== name).join(', ')}`, color: '#ffaa00' })
      if (note) lines.push({ text: note, color: item ? '#ff5555' : '#777777' })
      const canvas = await drawTooltip(lines, services.text, services.assets, view.device, view.ratio)
      if (this.tip === tip) tip.append(canvas)
    } catch { /* the tooltip is a convenience */ }
  }

  moveTip(event) {
    if (!this.tip) return
    this.tip.style.left = `${event.clientX + 8}px`
    this.tip.style.top = `${event.clientY - 8}px`
  }

  hideTip() {
    this.tip?.remove()
    this.tip = null
  }
}
