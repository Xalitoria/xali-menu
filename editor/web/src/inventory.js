// The window of a menu as a grid of slots, with the items where they are drawn. Clicking a slot picks it, dragging an item to
// another slot moves it. What the player sees when several items claim a slot is the one with the lowest priority.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon, settings } from './icons.js'
import { geometry, playerSlot } from './window.js'
import { viewOf, fitBox } from './scale.js'
import { decorations } from './decorations.js'
import { tooltipLines, drawTooltip } from './tooltip.js'
import { services } from './services.js'

/** The slots each item claims: its `slot` and `slots`, and the cells of the layout that its legend character stands for. */
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
   * @param {{select: (slot: number, items: string[]) => void}} events
   */
  constructor(store, events) {
    this.store = store
    this.events = events
    this.slot = null
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
   * the menu in the game and the menu does not use it, so it is grayed out; a slot of it that an item claims is shown with its item and
   * marked, since the menu does not draw there.
   */
  draw(box = { width: 1000, height: 700 }) {
    const menu = this.store.value
    const count = slotCount(menu)
    const g = geometry(menu)
    const view = this.viewFor(g, box)
    const spare = (available, size) => Math.min(MAX_ROOM, Math.max(MIN_ROOM, Math.floor(((available * view.ratio) / view.device - size) / 2) - 1))
    const room = { x: spare(box.width, g.width), y: spare(box.height, g.height) }
    const cell = view.css(18)
    const slots = bySlot(menu)
    // The coordinates of the game are those of the item, which lies one pixel inside the slot's border: the square of the slot starts one pixel up and to the left.
    const at = (x, y) => `left:${view.css(room.x + x - 1)}px;top:${view.css(room.y + y - 1)}px;width:${cell}px;height:${cell}px`
    const cellFor = (slot, names, place, extra = {}) => {
      const shown = names[0]
      const item = shown ? menu.items[shown] : null
      return h('div', {
        class: `slot ${this.slot === slot ? 'picked' : ''} ${names.length > 1 ? 'shared' : ''} ${extra.class ?? ''}`, 'data-slot': slot, style: place,
        draggable: Boolean(item && typeof item.slot === 'number' && !item.slots?.length),
        onclick: () => { this.slot = slot; this.events.select(slot, names) },
        ondragstart: (e) => e.dataTransfer.setData('text/plain', shown),
        ondragover: (e) => e.preventDefault(),
        ondrop: (e) => {
          e.preventDefault()
          const name = e.dataTransfer.getData('text/plain')
          if (name && menu.items[name]) { this.store.set(['items', name, 'slot'], slot); this.slot = slot }
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
    // The player's inventory: slots count to count + 26 for the three rows and count + 27 to count + 35 for the hotbar.
    for (let index = 0; index < 36; index++) {
      const slot = count + index
      const names = slots.get(slot) ?? []
      const place = playerSlot(g, index)
      grid.append(cellFor(slot, names, at(place.x, place.y), {
        class: names.length ? 'outside' : 'unhandled',
        note: names.length
          ? "This slot is in the player's inventory. The menu does not draw items there, so this item is never shown."
          : "The player's inventory. The menu does not use it: nothing is drawn here and a click does nothing.",
      }))
    }
    const background = services.window.draw(menu, { scale: view.device, ratio: view.ratio, margin: room })
    const width = view.css(g.width + 2 * room.x)
    const height = view.css(g.height + 2 * room.y)
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
