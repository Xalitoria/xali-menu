// The editing screen: the window of the menu in the middle, with the properties of the menu in the toolbar above it, the items in a list beside it,
// the properties of the picked item on the other side, and the glyphs below. The window fills the space that is left.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon } from './icons.js'
import { SCALES, viewOf } from './scale.js'
import { inventoryEntries, placesOf, removeClaim } from './placement.js'
import { firstFreeSlot, freeId, idProblem, renameItem, removeItem, duplicateItem } from './items.js'
import { INVENTORY_SLOTS } from './window.js'
import { services } from './services.js'
import { GlyphBar } from './glyphbar.js'
import { TEMPLATE, EMPTY, isPaged, listedSlots } from './pages.js'
import { pagesView, valuesBar } from './pagesPanel.js'

/** The first fields of an item's form. */
const ITEM_FIRST = ['material', 'name', 'lore', 'amount', 'slot', 'slots', 'inventory-slots', 'view', 'click']

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

const TOP_KEYS = ['title', 'size', 'type', 'items', 'inventory']

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
    this.stage = h('div', { class: 'stage', title: 'Drag the empty space to move the window, double click it to center' })
    this.grabbing()
    this.list = this.itemList()
    this.observe()
    return h('div', { class: 'editor' },
      this.topBar(),
      this.list,
      h('div', { class: 'stage-wrap' }, this.stage, this.app.painting && this.paintBanner(), this.app.placing && this.placeBanner(), this.pager = this.pagerView()),
      this.itemPanel(),
      this.glyphs.view())
  }

  /** Called once the screen is in the page, so the stage has its size: draws the window in it. */
  attached() {
    if (!this.stage?.isConnected) return
    this.placePopover()
    this.drawStage()
  }

  /** Puts the open panel of a group under its button, moved left or up as far as it takes to stay inside the window. */
  placePopover() {
    const panel = document.querySelector('.popover')
    const button = document.querySelector('.pop-button.active')
    if (!panel || !button) return
    const margin = 12
    const anchor = button.getBoundingClientRect()
    const width = Math.min(panel.offsetWidth, window.innerWidth - 2 * margin)
    const left = Math.max(margin, Math.min(anchor.left, window.innerWidth - width - margin))
    const top = anchor.bottom + 8
    panel.style.left = `${left}px`
    panel.style.top = `${top}px`
    panel.style.maxHeight = `${Math.max(160, window.innerHeight - top - margin)}px`
    if (!this.placing) { this.placing = true; window.addEventListener('resize', () => this.placePopover()) }
  }

  drawStage() {
    const { clientWidth: width, clientHeight: height } = this.stage
    if (!width || !height) return
    this.lastBox = this.boxKey()
    const { app } = this
    app.inventory.draw({ width, height }, { menu: app.shownMenu(), listed: new Set(listedSlots(this.menu.pages)), painting: app.painting })
    fill(this.stage, this.app.inventory.root)
    this.pager?.replaceWith(this.pager = this.pagerView())
    // The window opens in the middle of the stage, and stays where it was dragged to.
    if (this.app.centerStage) { this.pan = { x: 0, y: 0 }; this.app.centerStage = false }
    this.place()
  }

  /** Puts the window in the middle of the stage, moved by what was dragged; whole pixels, so its pixels stay sharp. */
  place() {
    const root = this.app.inventory.root
    const { clientWidth: width, clientHeight: height } = this.stage
    const pan = this.pan ?? (this.pan = { x: 0, y: 0 })
    // At least a corner of the window stays on the stage.
    const keep = 80
    pan.x = Math.max(-(width + root.offsetWidth) / 2 + keep, Math.min((width + root.offsetWidth) / 2 - keep, pan.x))
    pan.y = Math.max(-(height + root.offsetHeight) / 2 + keep, Math.min((height + root.offsetHeight) / 2 - keep, pan.y))
    root.style.left = `${Math.round((width - root.offsetWidth) / 2 + pan.x)}px`
    root.style.top = `${Math.round((height - root.offsetHeight) / 2 + pan.y)}px`
    this.stage.classList.toggle('moved', pan.x !== 0 || pan.y !== 0)
  }

  /**
   * Dragging the empty stage, or anywhere with the middle button or with Space held, moves the window; the wheel moves it too; a double click on the
   * empty stage puts it back in the middle. Dragging an item from slot to slot is the window's own and is left alone.
   */
  grabbing() {
    const stage = this.stage
    stage.addEventListener('pointerdown', (e) => {
      const empty = e.target === stage
      if (!(empty && e.button === 0) && e.button !== 1 && !(this.spaceDown && e.button === 0)) return
      e.preventDefault()
      const start = { x: e.clientX, y: e.clientY, pan: { ...this.pan } }
      stage.setPointerCapture(e.pointerId)
      stage.classList.add('grabbing')
      const move = (m) => { this.pan = { x: start.pan.x + m.clientX - start.x, y: start.pan.y + m.clientY - start.y }; this.place() }
      const up = () => { stage.classList.remove('grabbing'); stage.removeEventListener('pointermove', move); stage.removeEventListener('pointerup', up); stage.removeEventListener('pointercancel', up) }
      stage.addEventListener('pointermove', move)
      stage.addEventListener('pointerup', up)
      stage.addEventListener('pointercancel', up)
    })
    stage.addEventListener('dblclick', (e) => { if (e.target === stage) { this.pan = { x: 0, y: 0 }; this.place() } })
    stage.addEventListener('wheel', (e) => {
      e.preventDefault()
      this.pan = { x: this.pan.x - (e.shiftKey ? e.deltaY : e.deltaX), y: this.pan.y - (e.shiftKey ? 0 : e.deltaY) }
      this.place()
    }, { passive: false })
    if (!this.spaceListening) {
      this.spaceListening = true
      const space = (down) => (e) => {
        if (e.code !== 'Space' || /^(INPUT|TEXTAREA|SELECT|BUTTON)$/.test(e.target.tagName)) return
        this.spaceDown = down
        if (down) e.preventDefault()
        this.stage?.classList.toggle('grab', down)
      }
      window.addEventListener('keydown', space(true))
      window.addEventListener('keyup', space(false))
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
      h('div', { class: 'tool' }, h('span', null, 'GUI scale'),
        h('div', { class: 'tool-row' },
          this.select([['0', 'Fit'], ...SCALES.map((n) => [String(n), `${n}×`])], String(services.scale), (v) => { services.scale = Number(v); app.centerStage = true; this.drawStage() }),
          h('button', { title: 'Put the window back in the middle', onclick: () => { this.pan = { x: 0, y: 0 }; this.place() } }, 'Center'))))
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
      group.id === 'pages' ? pagesView(this.app) : this.app.form.object(this.app.schema.root, [], group.keys
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
    const view = viewOf(2)
    const problems = app.allProblems()
    const badIn = (section) => new Set(problems.filter((p) => p.path?.[0] === section).map((p) => p.path[1]))
    const bad = badIn('items')
    if (problems.some((p) => p.path?.[0] === 'pages' && p.path[1] === 'item')) bad.add(TEMPLATE)
    const sample = app.shownMenu().items?.['@element1']
    const inventoryIds = Object.keys(menu.inventory ?? {})
    return h('aside', { class: 'sidebar items' },
      h('div', { class: 'side-head' }, h('h2', null, 'Items', h('span', { class: 'count' }, ids.length)),
        h('button', { class: 'primary small', title: 'Add an item to the first free slot', onclick: () => this.add(null, 'items') }, '+ Add')),
      h('p', { class: 'list-hint muted', title: 'Pick an item, then Place it in more slots; or drag it from here onto a slot of the window' },
        'One item can be in many slots, in the menu and in the player\'s inventory under it. Drag one onto a slot, or pick it and place it.'),
      h('div', { class: 'item-rows' },
        menu.pages && this.pagedRow(TEMPLATE, sample ?? menu.pages.item ?? {}, 'Element', `each of ${listedSlots(menu.pages).length} slots · ${menu.pages.catalog?.type ?? 'no catalog'}`, bad.has(TEMPLATE), view),
        menu.pages?.empty && this.pagedRow(EMPTY, menu.pages.empty, 'When there are none', 'drawn when the catalog is empty', false, view),
        ids.length === 0 && !menu.pages && h('p', { class: 'muted pad' }, 'The menu has no items. Add one, or click a slot of the window.'),
        ids.map((id) => this.itemRow(id, 'items', view, bad)),
        inventoryIds.length > 0 && h('div', { class: 'side-head sub', title: "Items that are only drawn in the player's own inventory. Put one in a slot of the menu to make it an ordinary item of the menu too." },
          h('h2', null, 'Only in the inventory', h('span', { class: 'count' }, inventoryIds.length))),
        inventoryIds.map((id) => this.itemRow(id, 'inventory', view, badIn('inventory')))))
  }

  /** The entry of the list for the item [id] of [section]. It can be dragged onto a slot of the window. */
  itemRow(id, section, view, bad) {
    const { app } = this
    const item = this.menu[section][id]
    const active = id === app.picked.item && section === app.picked.section
    return h('button', { class: `item-row ${active ? 'active' : ''} ${bad.has(id) ? 'bad' : ''}`, 'data-id': id, 'data-section': section, title: `${id}: ${item.material ?? ''}`,
      draggable: true,
      ondragstart: (e) => { e.dataTransfer.effectAllowed = 'copy'; e.dataTransfer.setData('text/plain', JSON.stringify({ kind: 'item', name: id, section })) },
      onclick: () => app.pickItem(id, section) },
    h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio)),
    h('span', { class: 'item-text' }, h('b', null, id), h('small', null, `${placeText(placesOf(this.menu, id, section))} · ${item.material ?? ''}`)))
  }

  /** The entry of the list for the template of a paged menu, or for its item for no elements: they are items of `pages`, not of `items`. */
  pagedRow(id, item, label, detail, bad, view) {
    return h('button', { class: `item-row paged ${id === this.app.picked.item && this.app.picked.section === 'items' ? 'active' : ''} ${bad ? 'bad' : ''}`, 'data-id': id, title: detail, onclick: () => this.app.pickItem(id) },
      h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio)),
      h('span', { class: 'item-text' }, h('b', null, label), h('small', null, detail)))
  }

  /** The small bar under the window of a paged menu that turns the example pages, or says that the page of no elements is shown. */
  pagerView() {
    const { app } = this
    const listed = listedSlots(this.menu.pages).length
    if (!this.menu.pages?.item || listed === 0 || app.painting || app.placing) return h('div', { class: 'pager', hidden: true })
    if (app.picked.item === EMPTY) {
      return h('div', { class: 'pager' }, h('span', { class: 'muted' }, 'The page of a catalog with nothing to list'),
        h('button', { class: 'small', title: 'Show the elements again', onclick: () => app.pickItem(TEMPLATE) }, 'Show elements'))
    }
    const pages = app.previewPages()
    const live = app.live?.key === app.liveKey() ? app.live : null
    const source = app.liveElements() ? `${live.total} from the server${live.total > live.elements.length ? `, the first ${live.elements.length} shown` : ''}`
      : live?.pending ? 'example elements · asking the server' : live?.error ? `example elements · ${live.error}` : live ? 'example elements · the server lists none now' : 'example elements'
    return h('div', { class: 'pager' },
      h('button', { class: 'small', title: 'The page before', disabled: app.page <= 1, onclick: () => app.turnPage(-1) }, '‹'),
      h('span', null, `Page ${app.page} of ${pages}`, h('small', { class: 'muted', title: live?.error ?? '' }, ` · ${source}`)),
      h('button', { class: 'small', title: 'The page after', disabled: app.page >= pages, onclick: () => app.turnPage(1) }, '›'))
  }

  /** The strip over the window while its slots are being picked for the elements of a paged menu. */
  paintBanner() {
    const count = listedSlots(this.menu.pages).length
    return h('div', { class: 'paint-banner' },
      h('span', null, h('b', null, 'Pick the slots of the elements.'), ` Click a slot to put an element in it or take it out. ${count} ${count === 1 ? 'slot' : 'slots'} now.`),
      h('button', { class: 'primary small', onclick: () => this.app.paint(false) }, 'Done'))
  }

  /** The strip over the window while the slots of the picked item are being picked. */
  placeBanner() {
    const { picked } = this.app
    return h('div', { class: 'paint-banner' },
      h('span', null, h('b', null, `Place ${picked.item}.`), ' Click a slot, in the menu or in the player\'s inventory under it, to draw the item there or take it out.'),
      h('button', { class: 'primary small', onclick: () => this.app.place(false) }, 'Done'))
  }

  /** The slots of [area] that an item is drawn in. */
  taken(area) {
    const menu = this.menu
    if (area === 'inventory') return new Set(inventoryEntries(menu).keys())
    return new Set(Object.keys(menu.items ?? {}).flatMap((id) => { const p = placesOf(menu, id); return [...p.items, ...p.layout] }))
  }

  /**
   * Adds a new item to [slot] of [area] if it is given and free, otherwise to the picked slot if that is free, otherwise to the first free one. One for the
   * player's inventory is an item of `items` with `inventory-slots`, which can be used in the menu too.
   */
  add(slot = null, area = this.app.picked.area ?? 'items') {
    const { app } = this
    const menu = this.menu
    const count = area === 'inventory' ? INVENTORY_SLOTS : slotCount(menu)
    const taken = this.taken(area)
    const wanted = slot ?? (app.picked.area === area ? app.picked.slot : null)
    const at = wanted !== null && wanted !== undefined && wanted < count && !taken.has(wanted) ? wanted : firstFreeSlot(taken, count)
    const id = freeId(menu.items ?? {})
    const item = { material: 'stone' }
    if (at !== null) { if (area === 'inventory') item['inventory-slots'] = [at]; else item.slot = at }
    app.store.change((draft) => { (draft.items ??= {})[id] = item; return draft })
    app.picked = { slot: at, item: id, section: 'items', area }
    app.inventory.slot = at
    app.inventory.area = area
    app.draw()
  }

  // ---- the item, on the other side

  itemPanel() {
    const { app } = this
    const menu = this.menu
    const { section, area } = app.picked
    if (section === 'items' && isPaged(app.picked.item) && menu.pages) return this.pagedPanel(app.picked.item)
    const id = app.picked.item && menu[section]?.[app.picked.item] ? app.picked.item : null
    if (!id) return this.slotPanel()
    const own = app.allProblems().filter((p) => p.path?.[0] === section && p.path[1] === id)
    const name = h('input', { type: 'text', class: 'id-input', value: id, spellcheck: false, 'aria-label': 'Item id',
      oninput: () => name.classList.toggle('bad', Boolean(idProblem(menu, name.value.trim(), id, section))),
      onchange: () => this.rename(id, name.value.trim(), name, section),
      onkeydown: (e) => { if (e.key === 'Enter') name.blur() } })
    return h('aside', { class: 'sidebar props' },
      h('div', { class: 'side-head item-head' },
        h('label', { class: 'tool grow', title: section === 'items' ? 'The id the item is known by: the legend and messages use it' : 'The id the item is known by' }, h('span', null, 'Id'), name),
        h('button', { class: 'small', title: 'Copy the item', onclick: () => this.duplicate(id, section) }, 'Copy'),
        h('button', { class: 'danger small', title: 'Remove the item', onclick: () => this.remove(id, section) }, 'Delete')),
      own.length > 0 && h('ul', { class: 'item-problems' }, own.slice(0, 6).map((p) => h('li', null, h('code', null, p.path.slice(2).join('.') || 'item'), ' ', p.message))),
      this.placement(id, section, area),
      h('div', { class: 'props-body' }, app.form.object('Item', [section, id], { first: ITEM_FIRST })))
  }

  /**
   * Where the item [id] of [section] is drawn: a chip for each slot of the menu and of the player's inventory (click to see it, x to take the item out
   * of it), a note for the slots the layout gives it, and the button that lets slots of the window be clicked to put it in them or take it out.
   */
  placement(id, section, area) {
    const { app } = this
    const places = placesOf(this.menu, id, section)
    const here = (a, slot) => app.picked.slot === slot && (app.picked.area ?? 'items') === a
    const chip = (a, slot) => h('span', { class: `chip ${here(a, slot) ? 'active' : ''}` },
      h('button', { class: 'chip-label', title: 'Show this slot', onclick: () => this.showSlot(a, slot) }, `${a === 'items' ? 'Menu' : 'Inventory'} ${slot}`),
      h('button', { class: 'chip-x', title: `Do not draw ${id} in this slot`, 'aria-label': `Remove from slot ${slot}`, onclick: () => this.unplace(id, section, a, slot) }, '×'))
    const none = places.items.length + places.inventory.length + places.layout.length === 0
    return h('div', { class: 'placement' },
      h('div', { class: 'placement-head' },
        h('b', null, 'Drawn in'),
        h('button', { class: `small ${app.placing ? 'primary' : ''}`, title: 'Click slots of the window to draw this item in them, or take it out of them. The same item can be in as many as you like.',
          onclick: () => app.place(!app.placing) }, app.placing ? 'Done placing' : 'Place in more slots')),
      h('div', { class: 'chips' },
        none && h('span', { class: 'muted' }, 'No slot yet: the item is never shown.'),
        places.items.map((slot) => chip('items', slot)),
        places.layout.length > 0 && h('span', { class: 'chip layout', title: 'The slots of the layout that its character stands for. Change them in the Layout group.' }, `Layout ${places.layout.length === 1 ? 'slot' : 'slots'} ${places.layout.join(', ')}`),
        places.inventory.map((slot) => chip('inventory', slot))),
      section === 'inventory' && h('p', { class: 'muted note' }, "This item is only drawn in the player's inventory. Putting it in a slot of the menu makes it an item of the menu too."))
  }

  /** Shows the slot [slot] of [area] of the window as the picked one, keeping the item that is picked: the chip is one of its places. */
  showSlot(area, slot) {
    const { app } = this
    app.picked = { ...app.picked, slot, area }
    app.inventory.slot = slot
    app.inventory.area = area
    app.draw()
  }

  /** Takes the item [id] of [section] out of the slot [slot] of [area]. */
  unplace(id, section, area, slot) {
    const { app } = this
    app.store.change((draft) => removeClaim(draft, id, section, area, slot))
    app.draw()
  }

  /** The panel when no item is picked: the slot that is, with what can be put in it. */
  slotPanel() {
    const { app } = this
    const menu = this.menu
    const { slot, area = 'items' } = app.picked
    const inside = slot !== null && slot !== undefined && slot < (area === 'inventory' ? INVENTORY_SLOTS : slotCount(menu))
    if (!inside) {
      return h('aside', { class: 'sidebar props' },
        h('div', { class: 'empty-state' }, h('h2', null, 'No item picked'),
          h('p', { class: 'muted' }, 'Pick an item in the list or a slot of the window to change it. Drag an item to another slot to move it, hold Alt to draw it in both. Drag one from the list to draw it in a slot.')))
    }
    // What can go here: every item, one of the inventory's list moves to the menu's items if it is put in the window.
    const rows = [...Object.keys(menu.items ?? {}).map((id) => ['items', id]), ...Object.keys(menu.inventory ?? {}).map((id) => ['inventory', id])]
    const view = viewOf(2)
    return h('aside', { class: 'sidebar props' },
      h('div', { class: 'empty-state' },
        h('h2', null, area === 'inventory' ? `Inventory slot ${slot} is empty` : `Slot ${slot} is empty`),
        area === 'inventory' && h('p', { class: 'muted' }, "The player's own item stays there while the menu is open, unless an item is drawn here."),
        h('button', { class: 'primary', onclick: () => this.add(slot, area) }, '+ New item here')),
      rows.length > 0 && h('div', { class: 'reuse' },
        h('div', { class: 'side-head sub' }, h('h2', null, 'Or use one you have')),
        h('div', { class: 'item-rows' }, rows.map(([section, id]) => {
          const item = menu[section][id]
          return h('button', { class: 'item-row', title: `Draw ${id} in this slot as well`, onclick: () => app.drop({ kind: 'item', name: id, section }, { area, slot }, true) },
            h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio)),
            h('span', { class: 'item-text' }, h('b', null, id), h('small', null, `${placeText(placesOf(menu, id, section))} · ${item.material ?? ''}`)))
        }))))
  }

  /** The properties of the template of a paged menu, or of its item for no elements. */
  pagedPanel(id) {
    const { app } = this
    const empty = id === EMPTY
    const key = empty ? 'empty' : 'item'
    const own = app.allProblems().filter((p) => p.path?.[0] === 'pages' && p.path[1] === key)
    return h('aside', { class: 'sidebar props' },
      h('div', { class: 'side-head' },
        h('div', { class: 'head-text' }, h('h2', null, empty ? 'Item for no elements' : 'Item of each element'),
          h('small', { class: 'muted' }, empty ? 'Drawn in the first slot when the catalog has nothing to list.' : 'One item, drawn for every element of the page.')),
        empty && h('button', { class: 'danger small', title: 'Remove the item', onclick: () => { app.store.remove(['pages', 'empty']); app.picked.item = null } }, 'Delete')),
      own.length > 0 && h('ul', { class: 'item-problems' }, own.slice(0, 6).map((p) => h('li', null, h('code', null, p.path.slice(2).join('.') || 'item'), ' ', p.message))),
      !empty && valuesBar(app),
      // The slot of the item is the slot of the element: the item's own `slot` and `slots` are not used.
      h('div', { class: 'props-body' }, app.form.object('Item', ['pages', key], { first: ITEM_FIRST, hide: ['slot', 'slots', 'priority'] })))
  }

  rename(from, to, input, section = 'items') {
    const { app } = this
    const problem = idProblem(this.menu, to, from, section)
    if (problem) {
      app.setStatus(problem, true)
      input.value = from
      input.classList.remove('bad')
      return
    }
    if (to === from) return
    app.store.change((draft) => renameItem(draft, from, to, section))
    app.picked.item = to
    app.draw()
  }

  duplicate(id, section = 'items') {
    const { app } = this
    const menu = this.menu
    const area = section === 'inventory' ? 'inventory' : 'items'
    const made = freeId(menu[section], `${id}-`)
    const free = typeof menu[section][id].slot === 'number' ? firstFreeSlot(this.taken(area), area === 'inventory' ? INVENTORY_SLOTS : slotCount(menu)) : null
    app.store.change((draft) => duplicateItem(draft, id, made, free, section))
    app.picked = { slot: free, item: made, section, area }
    app.inventory.slot = free
    app.inventory.area = area
    app.draw()
  }

  remove(id, section = 'items') {
    const { app } = this
    app.store.change((draft) => removeItem(draft, id, section))
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

/** The same for the places of an item (`placesOf`): its slots of the menu, with those of the layout, and its slots of the player's inventory after them. */
export function placeText({ items, layout, inventory }) {
  const menu = [...new Set([...items, ...layout])].sort((a, b) => a - b)
  const rows = [...inventory].sort((a, b) => a - b)
  if (menu.length === 0 && rows.length === 0) return 'no slot'
  return [menu.length && slotText(menu), rows.length && `inventory ${rows.length === 1 ? 'slot' : 'slots'} ${rows.length <= 3 ? rows.join(', ') : `×${rows.length}`}`].filter(Boolean).join(' · ')
}
