// The window of a menu as a grid of slots, with the items where they are drawn. Clicking a slot picks it, dragging an item to
// another slot moves it. What the player sees when several items claim a slot is the one with the lowest priority.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon } from './icons.js'
import { render as renderText } from './mctext.js'

const COLUMNS = { hopper: 5, dispenser: 3, dropper: 3, crafter: 3 }

/** One slot is 18 pixels of the game; the window is drawn at three times that. */
const CELL = 54

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

export class Inventory {
  /**
   * @param {import('./store.js').Store} store
   * @param {{select: (slot: number, items: string[]) => void, title: (text: string) => HTMLElement}} events
   */
  constructor(store, events) {
    this.store = store
    this.events = events
    this.slot = null
    this.root = h('div', { class: 'inventory' })
  }

  draw() {
    const menu = this.store.value
    const count = slotCount(menu)
    const type = String(menu.type ?? 'chest').toLowerCase()
    const columns = COLUMNS[type] ?? 9
    const slots = bySlot(menu)
    const grid = h('div', { class: 'slots', style: `grid-template-columns:repeat(${columns}, ${CELL}px)` })
    for (let slot = 0; slot < count; slot++) {
      const names = slots.get(slot) ?? []
      const shown = names.find((n) => true)
      const item = shown ? menu.items[shown] : null
      const cell = h('div', {
        class: `slot ${this.slot === slot ? 'picked' : ''} ${names.length > 1 ? 'shared' : ''}`, 'data-slot': slot,
        title: item ? this.describe(shown, item, names) : `slot ${slot}`,
        draggable: Boolean(item && typeof item.slot === 'number' && !item.slots?.length),
        onclick: () => { this.slot = slot; this.events.select(slot, names); this.draw() },
        ondragstart: (e) => e.dataTransfer.setData('text/plain', shown),
        ondragover: (e) => e.preventDefault(),
        ondrop: (e) => {
          e.preventDefault()
          const name = e.dataTransfer.getData('text/plain')
          if (name && menu.items[name]) { this.store.set(['items', name, 'slot'], slot); this.slot = slot }
        },
      }, item && icon(item),
      item && (item.amount ?? 1) > 1 && h('span', { class: 'amount' }, item.amount),
      h('span', { class: 'number' }, slot))
      grid.append(cell)
    }
    // With a resource pack the window is a picture with the glyph of the title in it, and the slots lie over it where the game puts them.
    const background = this.events.window?.(menu)
    if (background) {
      grid.classList.add('layered')
      fill(this.root, h('div', { class: 'window layered-window', style: `width:${background.width}px;height:${background.height}px` }, background, grid))
    } else {
      fill(this.root, h('div', { class: 'window' }, h('div', { class: 'title' }, this.events.title(menu.title ?? '')), grid))
    }
  }

  describe(name, item, names) {
    const lines = [`${name}: ${item.material}`]
    if (item.name) lines.push(item.name)
    if (names.length > 1) lines.push(`also claimed by ${names.filter((n) => n !== name).join(', ')}`)
    return lines.join('\n')
  }
}
