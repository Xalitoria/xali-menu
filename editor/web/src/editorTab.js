// The editing screen: the window of the menu in the middle, with the properties of the menu in the toolbar above it, the items in a list beside it,
// the properties of the picked item on the other side, and the glyphs below. The window fills the space that is left.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon } from './icons.js'
import { SCALES, viewOf } from './scale.js'
import { placements } from './inventory.js'
import { firstFreeSlot, freeId, idProblem, renameItem, removeItem, duplicateItem } from './items.js'
import { services } from './services.js'
import { GlyphBar } from './glyphbar.js'

/** The first fields of an item's form. */
const ITEM_FIRST = ['material', 'name', 'lore', 'amount', 'slot', 'slots', 'view', 'click']

/** The inventory types the menu file accepts and the editor can draw. */
export const TYPES = [['chest', 'Chest'], ['hopper', 'Hopper (5 slots)'], ['dispenser', 'Dispenser (9 slots)'], ['dropper', 'Dropper (9 slots)'], ['shulker_box', 'Shulker box (27 slots)']]

/** The rows a chest can have, as the choices of `size`. */
export const SIZES = [1, 2, 3, 4, 5, 6].map((rows) => [String(rows * 9), `${rows} ${rows === 1 ? 'row' : 'rows'} (${rows * 9} slots)`])

/** The properties of the menu that are not in the first row of the toolbar, grouped by what they are for; each group is a button with a panel. */
export const GROUPS = [
  { id: 'opening', label: 'Opening', keys: ['commands', 'register', 'args', 'args-usage', 'complete', 'open', 'open-with'], hint: 'How players open the menu, and who may.' },
  { id: 'events', label: 'Events', keys: ['on-open', 'on-close', 'refresh'], hint: 'What happens when the menu opens or closes, and how often it is drawn again.' },
  { id: 'layout', label: 'Layout', keys: ['layout', 'legend'], hint: 'The menu as it looks, in rows of nine characters, for placing items by.' },
  { id: 'pages', label: 'Pages', keys: ['pages'], hint: 'List the elements of a catalog, a page at a time.' },
  { id: 'animation', label: 'Animation', keys: ['animation'], hint: 'Frames that play over the menu once it is open.' },
]

const TOP_KEYS = ['title', 'size', 'type', 'items']

export class EditorTab {
  /** @param {import('./app.js').App} app */
  constructor(app) {
    this.app = app
    /** The id of the group whose panel is open, or null. */
    this.popover = null
    this.glyphs = new GlyphBar(app)
    this.observer = null
  }

  get menu() { return this.app.store.value }

  // ---- the screen

  view() {
    this.stage = h('div', { class: 'stage' })
    this.list = this.itemList()
    this.observe()
    return h('div', { class: 'editor' },
      this.topBar(),
      this.list,
      this.stage,
      this.itemPanel(),
      this.glyphs.view())
  }

  /** Called once the screen is in the page, so the stage has its size: draws the window in it. */
  attached() {
    if (!this.stage?.isConnected) return
    this.drawStage()
  }

  drawStage() {
    const { clientWidth: width, clientHeight: height } = this.stage
    if (!width || !height) return
    this.lastBox = this.boxKey()
    this.app.inventory.draw({ width, height })
    fill(this.stage, this.app.inventory.root)
    // A window that is bigger than the stage opens in the middle of it.
    if (this.app.centerStage) {
      this.stage.scrollLeft = (this.stage.scrollWidth - width) / 2
      this.stage.scrollTop = (this.stage.scrollHeight - height) / 2
      this.app.centerStage = false
    }
  }

  /** The size of the stage and the pixel ratio, which together decide how the window is drawn. */
  boxKey() { return `${this.stage.clientWidth}x${this.stage.clientHeight}@${window.devicePixelRatio || 1}` }

  /** Draws the window again when the stage changes size (the browser window, a panel that grows) or the zoom changes the pixel ratio. */
  observe() {
    this.observer?.disconnect()
    const changed = () => {
      if (!this.stage.isConnected || this.boxKey() === this.lastBox) return
      clearTimeout(this.resizing)
      this.resizing = setTimeout(() => this.drawStage(), 60)
    }
    this.observer = new ResizeObserver(changed)
    this.observer.observe(this.stage)
    if (!this.listening) { this.listening = true; window.addEventListener('resize', changed) }
  }

  /** Brings the window and the list of items up to date after a change that did not draw the screen (typing in a field, which would lose the cursor). */
  refresh() {
    clearTimeout(this.refreshing)
    this.refreshing = setTimeout(() => {
      if (!this.stage?.isConnected) return
      const next = this.itemList()
      this.list.replaceWith(next)
      this.list = next
      this.drawStage()
    }, 200)
  }

  // ---- the toolbar above the window

  topBar() {
    const { app } = this
    const menu = this.menu
    const field = (key) => app.schema.field(app.schema.root, key)
    const control = (key) => app.form.control(field(key).type, [key], menu[key], { ...field(key), compact: true })
    const chest = String(menu.type ?? 'chest').toLowerCase() === 'chest'
    const known = new Set([...TOP_KEYS, ...GROUPS.flatMap((g) => g.keys)])
    const others = app.schema.fields(app.schema.root).map((f) => f.key).filter((k) => !known.has(k))
    const groups = [...GROUPS, ...(others.length || Object.keys(menu).some((k) => !known.has(k) && !others.includes(k))
      ? [{ id: 'more', label: 'More', keys: null, hint: 'The rest of the properties of the menu.' }] : [])]
    return h('div', { class: 'toolbar top' },
      h('label', { class: 'tool grow' }, h('span', null, 'Title'), control('title')),
      h('label', { class: 'tool' }, h('span', null, 'Type'), control('type')),
      chest && h('label', { class: 'tool' }, h('span', null, 'Size'), control('size')),
      h('span', { class: 'divider' }),
      h('div', { class: 'tool-group' }, groups.map((group) => this.groupButton(group, known))),
      h('span', { class: 'divider' }),
      h('label', { class: 'tool' }, h('span', null, 'GUI scale'),
        this.select([['0', 'Fit'], ...SCALES.map((n) => [String(n), `${n}×`])], String(services.scale), (v) => { services.scale = Number(v); app.centerStage = true; this.drawStage() })))
  }

  /** The number of the keys of the group that the menu sets. */
  count(group, known) {
    const menu = this.menu
    return Object.keys(menu).filter((k) => (group.keys ? group.keys.includes(k) : !known.has(k))).length
  }

  groupButton(group, known) {
    const open = this.popover === group.id
    const count = this.count(group, known)
    const panel = open && h('div', { class: 'popover', role: 'dialog' },
      h('h3', null, group.label),
      h('p', { class: 'muted' }, group.hint),
      this.app.form.object(this.app.schema.root, [], group.keys
        ? { only: group.keys }
        : { hide: [...known] }))
    return h('div', { class: 'pop-wrap' },
      h('button', { class: `pop-button ${open ? 'active' : ''} ${count ? 'set' : ''}`, 'aria-expanded': open, title: group.hint,
        onclick: () => { this.popover = open ? null : group.id; this.app.draw() } },
        group.label, count > 0 && h('span', { class: 'count' }, count)),
      panel)
  }

  /** The id of the group of properties that holds [key], or null for a key of the toolbar itself. */
  groupOf(key) {
    if (!key || TOP_KEYS.includes(key)) return null
    return GROUPS.find((g) => g.keys.includes(key))?.id ?? 'more'
  }

  /** A select of `[value, label]`. */
  select(options, current, change) {
    const select = h('select', { onchange: () => change(select.value) }, options.map(([value, label]) => h('option', { value, selected: value === current }, label)))
    return select
  }

  // ---- the items, beside the window

  itemList() {
    const { app } = this
    const menu = this.menu
    const ids = Object.keys(menu.items ?? {})
    const claimed = placements(menu)
    const view = viewOf(2)
    const bad = new Set(app.allProblems().filter((p) => p.path?.[0] === 'items').map((p) => p.path[1]))
    return h('aside', { class: 'sidebar items' },
      h('div', { class: 'side-head' }, h('h2', null, 'Items', h('span', { class: 'count' }, ids.length)),
        h('button', { class: 'primary small', title: 'Add an item to the first free slot', onclick: () => this.add() }, '+ Add')),
      h('div', { class: 'item-rows' },
        ids.length === 0 && h('p', { class: 'muted pad' }, 'The menu has no items. Add one, or click a slot of the window.'),
        ids.map((id) => {
          const item = menu.items[id]
          const slots = [...(claimed.get(id) ?? [])].sort((a, b) => a - b)
          return h('button', { class: `item-row ${id === app.picked.item ? 'active' : ''} ${bad.has(id) ? 'bad' : ''}`, 'data-id': id, title: `${id}: ${item.material ?? ''}`,
            onclick: () => app.pickItem(id) },
          h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio)),
          h('span', { class: 'item-text' }, h('b', null, id), h('small', null, `${slotText(slots)} · ${item.material ?? ''}`)))
        })))
  }

  /** Adds an item to the picked slot if it is free, otherwise to the first free one. */
  add(slot = null) {
    const { app } = this
    const menu = this.menu
    const count = slotCount(menu)
    const taken = new Set([...placements(menu).values()].flatMap((set) => [...set]))
    const wanted = slot ?? app.picked.slot
    const at = wanted !== null && wanted !== undefined && !taken.has(wanted) ? wanted : firstFreeSlot(taken, count)
    const id = freeId(menu.items ?? {})
    app.store.set(['items', id], at === null ? { material: 'stone' } : { material: 'stone', slot: at })
    app.picked = { slot: at, item: id }
    app.inventory.slot = at
    app.draw()
  }

  // ---- the item, on the other side

  itemPanel() {
    const { app } = this
    const menu = this.menu
    const id = app.picked.item && menu.items?.[app.picked.item] ? app.picked.item : null
    if (!id) {
      const slot = app.picked.slot
      return h('aside', { class: 'sidebar props' },
        h('div', { class: 'empty-state' },
          slot !== null && slot !== undefined && slot < slotCount(menu)
            ? [h('h2', null, `Slot ${slot} is empty`), h('button', { class: 'primary', onclick: () => this.add(slot) }, '+ Add an item here')]
            : [h('h2', null, 'No item picked'), h('p', { class: 'muted' }, 'Pick an item in the list or a slot of the window to change it. Drag an item to another slot to move it.')]))
    }
    const own = app.allProblems().filter((p) => p.path?.[0] === 'items' && p.path[1] === id)
    const name = h('input', { type: 'text', class: 'id-input', value: id, spellcheck: false, 'aria-label': 'Item id',
      oninput: () => name.classList.toggle('bad', Boolean(idProblem(menu, name.value.trim(), id))),
      onchange: () => this.rename(id, name.value.trim(), name),
      onkeydown: (e) => { if (e.key === 'Enter') name.blur() } })
    return h('aside', { class: 'sidebar props' },
      h('div', { class: 'side-head item-head' },
        h('label', { class: 'tool grow', title: 'The id the item is known by: the legend and messages use it' }, h('span', null, 'Id'), name),
        h('button', { class: 'small', title: 'Copy the item', onclick: () => this.duplicate(id) }, 'Copy'),
        h('button', { class: 'danger small', title: 'Remove the item', onclick: () => this.remove(id) }, 'Delete')),
      own.length > 0 && h('ul', { class: 'item-problems' }, own.slice(0, 6).map((p) => h('li', null, h('code', null, p.path.slice(2).join('.') || 'item'), ' ', p.message))),
      h('div', { class: 'props-body' }, app.form.object('Item', ['items', id], { first: ITEM_FIRST })))
  }

  rename(from, to, input) {
    const { app } = this
    const problem = idProblem(this.menu, to, from)
    if (problem) {
      app.setStatus(problem, true)
      input.value = from
      input.classList.remove('bad')
      return
    }
    if (to === from) return
    app.store.change((draft) => renameItem(draft, from, to))
    app.picked.item = to
    app.draw()
  }

  duplicate(id) {
    const { app } = this
    const menu = this.menu
    const taken = new Set([...placements(menu).values()].flatMap((set) => [...set]))
    const made = freeId(menu.items, `${id}-`)
    const free = typeof menu.items[id].slot === 'number' ? firstFreeSlot(taken, slotCount(menu)) : null
    app.store.change((draft) => duplicateItem(draft, id, made, free))
    app.picked = { slot: free, item: made }
    app.inventory.slot = free
    app.draw()
  }

  remove(id) {
    const { app } = this
    app.store.change((draft) => removeItem(draft, id))
    app.picked.item = null
    app.draw()
  }
}

/** "slot 4", "slots 1, 2", "6 slots" or "no slot", for the line under an item's id. */
export function slotText(slots) {
  if (slots.length === 0) return 'no slot'
  if (slots.length === 1) return `slot ${slots[0]}`
  return slots.length <= 3 ? `slots ${slots.join(', ')}` : `${slots.length} slots`
}
