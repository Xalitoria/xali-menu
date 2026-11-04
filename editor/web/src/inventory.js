// The window of a menu as a grid of slots, with the items where they are drawn. Clicking a slot picks it, dragging an item to
// another slot moves it. What the player sees when several items claim a slot is the one with the lowest priority.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon, settings } from './icons.js'
import { geometry } from './window.js'
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

  /** The GUI scale to draw at: the one chosen, or the biggest (up to 4) at which the window itself fits [available] pixels across (the room around it scrolls). */
  scaleFor(g, available) {
    if (services.scale) return services.scale
    const fit = Math.floor((available || 1000) / (g.width + 16))
    return Math.max(1, Math.min(4, fit))
  }

  /** The window as the game draws it with the slots of the menu over it, in the middle of the room around it; the items are drawn in as their models arrive. */
  draw(available = 0) {
    const menu = this.store.value
    const count = slotCount(menu)
    const g = geometry(menu)
    const S = this.scaleFor(g, available)
    const room = services.room
    const cell = 18 * S
    const slots = bySlot(menu)
    // The coordinates of the game are those of the item, which lies one pixel inside the slot's border: the square of the slot starts one pixel up and to the left.
    const grid = h('div', { class: 'slots layered', style: `left:${(room + g.x - 1) * S}px;top:${(room + g.y - 1) * S}px;grid-template-columns:repeat(${g.columns}, ${cell}px)` })
    for (let slot = 0; slot < count; slot++) {
      const names = slots.get(slot) ?? []
      const shown = names[0]
      const item = shown ? menu.items[shown] : null
      grid.append(h('div', {
        class: `slot ${this.slot === slot ? 'picked' : ''} ${names.length > 1 ? 'shared' : ''}`, 'data-slot': slot, style: `width:${cell}px;height:${cell}px`,
        draggable: Boolean(item && typeof item.slot === 'number' && !item.slots?.length),
        onclick: () => { this.slot = slot; this.events.select(slot, names); this.draw(available) },
        ondragstart: (e) => e.dataTransfer.setData('text/plain', shown),
        ondragover: (e) => e.preventDefault(),
        ondrop: (e) => {
          e.preventDefault()
          const name = e.dataTransfer.getData('text/plain')
          if (name && menu.items[name]) { this.store.set(['items', name, 'slot'], slot); this.slot = slot }
        },
        onmouseenter: (e) => this.showTip(item, shown, names, slot, e, S),
        onmousemove: (e) => this.moveTip(e),
        onmouseleave: () => this.hideTip(),
      }, item && icon(item, S), item && decorations(item, services.text, settings.resolved.get(item.material)?.maxDamage ?? 0, S), h('span', { class: 'number' }, slot)))
    }
    const background = services.window.draw(menu, { scale: S, margin: room })
    const width = (g.width + 2 * room) * S
    const height = (g.height + 2 * room) * S
    fill(this.root, h('div', { class: 'window layered-window', style: `width:${width}px;height:${height}px` }, background, grid))
  }

  /** The tooltip of the item the pointer is over, next to the pointer; an empty slot says which slot it is. */
  async showTip(item, name, names, slot, event, scale = 3) {
    this.hideTip()
    const tip = h('div', { class: 'tooltip' })
    this.tip = tip
    document.body.append(tip)
    this.moveTip(event)
    try {
      const lang = (await services.assets.json('minecraft', 'lang', 'en_us')) ?? {}
      const lines = item ? tooltipLines(item, lang, services.renderer.knownName?.(item)) : [{ text: `Slot ${slot}`, color: '#aaaaaa' }]
      if (item && names.length > 1) lines.push({ text: `Also claimed by ${names.filter((n) => n !== name).join(', ')}`, color: '#ffaa00' })
      const canvas = await drawTooltip(lines, services.text, services.assets, scale)
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
