// The window of a menu as a grid of slots, with the items where they are drawn. Clicking a slot picks it, dragging an item to
// another slot moves it. What the player sees when several items claim a slot is the one with the lowest priority.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon } from './icons.js'
import { geometry, SCALE } from './window.js'
import { decorations } from './decorations.js'
import { tooltipLines, drawTooltip } from './tooltip.js'
import { services } from './services.js'

/** One slot is 18 pixels of the game; the window is drawn at three times that. */
const CELL = 18 * SCALE

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
   * @param {{select: (slot: number, items: string[]) => void}} events
   */
  constructor(store, events) {
    this.store = store
    this.events = events
    this.slot = null
    this.root = h('div', { class: 'inventory' })
    this.tip = null
  }

  /** The window as the game draws it with the slots of the menu over it; the items are drawn in as their models arrive. */
  draw() {
    const menu = this.store.value
    const count = slotCount(menu)
    const g = geometry(menu)
    const slots = bySlot(menu)
    const grid = h('div', { class: 'slots layered', style: `left:${g.x * SCALE}px;top:${g.y * SCALE}px;grid-template-columns:repeat(${g.columns}, ${CELL}px)` })
    for (let slot = 0; slot < count; slot++) {
      const names = slots.get(slot) ?? []
      const shown = names[0]
      const item = shown ? menu.items[shown] : null
      grid.append(h('div', {
        class: `slot ${this.slot === slot ? 'picked' : ''} ${names.length > 1 ? 'shared' : ''}`, 'data-slot': slot,
        draggable: Boolean(item && typeof item.slot === 'number' && !item.slots?.length),
        onclick: () => { this.slot = slot; this.events.select(slot, names); this.draw() },
        ondragstart: (e) => e.dataTransfer.setData('text/plain', shown),
        ondragover: (e) => e.preventDefault(),
        ondrop: (e) => {
          e.preventDefault()
          const name = e.dataTransfer.getData('text/plain')
          if (name && menu.items[name]) { this.store.set(['items', name, 'slot'], slot); this.slot = slot }
        },
        onmouseenter: (e) => this.showTip(item, shown, names, slot, e),
        onmousemove: (e) => this.moveTip(e),
        onmouseleave: () => this.hideTip(),
      }, item && icon(item), item && decorations(item, services.text), h('span', { class: 'number' }, slot)))
    }
    const background = services.window.draw(menu)
    fill(this.root, h('div', { class: 'window layered-window', style: `width:${g.width * SCALE}px;height:${g.height * SCALE}px` }, background, grid))
  }

  /** The tooltip of the item the pointer is over, next to the pointer; an empty slot says which slot it is. */
  async showTip(item, name, names, slot, event) {
    this.hideTip()
    const tip = h('div', { class: 'tooltip' })
    this.tip = tip
    document.body.append(tip)
    this.moveTip(event)
    try {
      const lang = (await services.assets.json('minecraft', 'lang', 'en_us')) ?? {}
      const lines = item ? tooltipLines(item, lang, services.renderer.knownName?.(item)) : [{ text: `Slot ${slot}`, color: '#aaaaaa' }]
      if (item && names.length > 1) lines.push({ text: `Also claimed by ${names.filter((n) => n !== name).join(', ')}`, color: '#ffaa00' })
      const canvas = await drawTooltip(lines, services.text, services.assets)
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
