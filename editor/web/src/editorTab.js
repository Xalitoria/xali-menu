// The editing screen: the window of the menu in the middle, with the properties of the menu in the toolbar above it, the items in a list beside it,
// the properties of the picked item on the other side, and the glyphs below. The window fills the space that is left.

import { h, fill } from './dom.js'
import { slotCount } from './check.js'
import { icon, settings as iconSettings } from './icons.js'
import { SCALES, viewOf } from './scale.js'
import { inventoryEntries, placesOf, removeClaim } from './placement.js'
import { firstFreeSlot, freeId, idProblem, renameItem, removeItem, duplicateItem } from './items.js'
import { INVENTORY_SLOTS } from './window.js'
import { services } from './services.js'
import { GlyphBar } from './glyphbar.js'
import { TEMPLATE, EMPTY, isPaged, listedSlots } from './pages.js'
import { pagesView, valuesBar } from './pagesPanel.js'
import { matrixEditor } from './layouteditor.js'
import { DEVICES, deviceById, presentationFor, titleForDevice } from './bedrock.js'
import { bedrockBanner, bedrockFormView } from './bedrockPreview.js'
import { evaluateItemMeta, itemPropertyGroup, GROUP_ORDER } from './itemMeta.js'

const UNSAFE_KEY = 'xm-unsafe-mode'
function readUnsafeFlag() {
  try { return localStorage.getItem(UNSAFE_KEY) === '1' } catch { return false }
}

/** The first fields of an item's form. */
const ITEM_FIRST = ['material', 'skull', 'name', 'lore', 'amount', 'slot', 'slots', 'inventory-slots', 'view', 'click', 'bedrock']

/** The inventory types the menu file accepts and the editor can draw. */
export const TYPES = [['chest', 'Chest'], ['hopper', 'Hopper (5 slots)'], ['dispenser', 'Dispenser (9 slots)'], ['dropper', 'Dropper (9 slots)'], ['shulker_box', 'Shulker box (27 slots)']]

/** The rows a chest can have, as the choices of `size`. */
export const SIZES = [1, 2, 3, 4, 5, 6].map((rows) => [String(rows * 9), `${rows} ${rows === 1 ? 'row' : 'rows'} (${rows * 9} slots)`])

/** The properties of the menu that are not in the first row of the toolbar, grouped by what they are for; each group is a button with a panel. */
export const GROUPS = [
  { id: 'opening', label: 'Opening', keys: ['commands', 'register', 'args', 'args-usage', 'complete', 'open', 'open-with'], hint: 'How players open the menu, and who may.' },
  { id: 'events', label: 'Events', keys: ['on-open', 'on-close', 'refresh'], hint: 'What happens when the menu opens or closes, and how often it is drawn again.' },
  { id: 'matrix', label: 'Matrix', keys: ['matrix'], hint: 'Draw the whole menu as a single character grid mapped to items.' },
  { id: 'pages', label: 'Pages', keys: ['pages'], hint: 'List the elements of a catalog, a page at a time.' },
  { id: 'animation', label: 'Animation', keys: ['animation'], hint: 'Frames that play over the menu once it is open.' },
  { id: 'bedrock', label: 'Bedrock', keys: ['bedrock'], hint: 'Bedrock Edition forms, device targeting and touch/controller interaction overrides.' },
]

const TOP_KEYS = ['title', 'size', 'type', 'items']

export class EditorTab {
  /** @param {import('./app.js').App} app */
  constructor(app) {
    this.app = app
    /** The id of the group whose panel is open, or null. */
    this.popover = null
    /** Which client device the stage previews (`java`, `bedrock-pc`, `bedrock-mobile`, `bedrock-console`). */
    this.device = 'java'
    /** Assumed server `bedrock.default-mode` when previewing Bedrock devices without explicit form settings. */
    this.bedrockDefault = 'touch-form'
    /** When false (default), only item meta applicable to the item's material is shown; toggling off never removes existing keys. */
    this.unsafeMode = readUnsafeFlag()
    /** Active pane on small screens (`stage` | `list` | `props` | `glyphs`). */
    this.mobilePane = 'stage'
    /** Whether the slide-over items drawer is open on medium/tablet screens. */
    this.drawerOpen = false
    /** Whether secondary menu toolbar controls are expanded on small screens. */
    this.toolbarOpen = false
    this.glyphs = new GlyphBar(app)
    this.observer = null
  }

  setUnsafeMode(on) {
    this.unsafeMode = Boolean(on)
    try { localStorage.setItem(UNSAFE_KEY, this.unsafeMode ? '1' : '0') } catch { /* ignore storage errors */ }
    this.app.draw()
  }

  get menu() { return this.app.store.value }

  // ---- the screen

  view() {
    this.stage = h('div', { class: 'stage', title: 'Drag the empty space to move the window, double click it to center' })
    this.grabbing()
    this.list = this.itemList()
    this.observe()
    const hasGlyphs = this.glyphs.available()
    if (this.mobilePane === 'glyphs' && !hasGlyphs) this.mobilePane = 'stage'
    return h('div', {
      class: 'editor',
      'data-pane': this.mobilePane,
      'data-drawer': this.drawerOpen ? 'open' : 'closed',
      'data-toolbar': this.toolbarOpen ? 'open' : 'closed',
    },
    this.topBar(),
    this.mobileNav(hasGlyphs),
    this.drawerOpen && h('div', { class: 'drawer-backdrop', onclick: () => { this.drawerOpen = false; this.app.draw() } }),
    this.list,
    h('div', { class: 'stage-wrap' },
      this.stage,
      this.app.painting && this.paintBanner(),
      this.app.placing && this.placeBanner(),
      this.pager = this.pagerView(),
      this.mobileSelectionBar()),
    this.itemPanel(),
    this.glyphs.view())
  }

  /** Segmented pane bar shown on small screens so canvas, items, inspector and glyphs never stack into an endless wall. */
  mobileNav(hasGlyphs) {
    const count = Object.keys(this.menu.items ?? {}).length
    const picked = this.app.picked.item
    const tab = (id, label, badge = null) => h('button', {
      class: `mobile-tab ${this.mobilePane === id ? 'active' : ''}`,
      'aria-pressed': this.mobilePane === id,
      onclick: () => { this.mobilePane = id; this.app.draw() },
    }, label, badge !== null && h('span', { class: 'count' }, badge))
    return h('nav', { class: 'mobile-nav', 'aria-label': 'Editor panes' },
      tab('stage', 'Canvas'),
      tab('list', 'Items', count),
      tab('props', picked ? 'Item' : 'Slot', picked ? '•' : null),
      hasGlyphs && tab('glyphs', 'Glyphs'))
  }

  /** Compact bottom bar on small screens after tapping a slot on the canvas, for 1-tap editing or item creation. */
  mobileSelectionBar() {
    const { app } = this
    if (app.painting || app.placing) return null
    const { slot, item, area } = app.picked
    if (item && (isPaged(item) || this.menu.items?.[item])) {
      const label = item === TEMPLATE ? 'Element template' : item === EMPTY ? 'Empty item' : item
      return h('div', { class: 'stage-selection' },
        h('span', { class: 'stage-sel-text' }, h('b', null, label), slot !== null && h('small', { class: 'muted' }, ` · ${area === 'inventory' ? 'Inv' : 'Slot'} ${slot}`)),
        h('button', { class: 'primary small', onclick: () => { this.mobilePane = 'props'; app.draw() } }, 'Edit item →'))
    }
    if (slot !== null && slot !== undefined) {
      return h('div', { class: 'stage-selection' },
        h('span', { class: 'stage-sel-text' }, h('b', null, area === 'inventory' ? `Inventory ${slot}` : `Slot ${slot}`), h('small', { class: 'muted' }, ' · empty')),
        h('button', { class: 'primary small', onclick: () => this.add(slot, area) }, '+ New item'),
        h('button', { class: 'small', onclick: () => { this.mobilePane = 'props'; app.draw() } }, 'Pick existing'))
    }
    return null
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
    const device = deviceById(this.device)
    const presentation = presentationFor(this.menu, device, this.bedrockDefault)
    const isBedrock = device.edition === 'BEDROCK'
    this.stage.classList.toggle('bedrock-stage', isBedrock && presentation.kind !== 'inventory')
    if (isBedrock && presentation.kind !== 'inventory') {
      fill(this.stage,
        bedrockBanner(app, device, presentation, this.bedrockDefault),
        h('div', { class: 'bedrock-canvas' }, bedrockFormView(app, device, presentation)))
      this.pager?.replaceWith(this.pager = this.pagerView())
      return
    }
    const shown = app.shownMenu()
    const preview = isBedrock ? { ...shown, title: titleForDevice(shown, device) } : shown
    app.inventory.draw({ width, height }, { menu: preview, listed: new Set(listedSlots(this.menu.pages)), painting: app.painting })
    fill(this.stage, ...(isBedrock ? [bedrockBanner(app, device, presentation, this.bedrockDefault), this.app.inventory.root] : [this.app.inventory.root]))
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
    const itemCount = Object.keys(menu.items ?? {}).length
    const activeGroups = groups.reduce((n, g) => n + (this.count(g, known) > 0 ? 1 : 0), 0)
    return h('div', { class: 'toolbar top' },
      h('div', { class: 'toolbar-main' },
        h('button', {
          class: `drawer-toggle ${this.drawerOpen ? 'active' : ''}`,
          title: 'Show or hide the items list',
          onclick: () => { this.drawerOpen = !this.drawerOpen; app.draw() },
        }, '☰ Items', h('span', { class: 'count' }, itemCount)),
        h('label', { class: 'tool grow' }, h('span', null, 'Title'), control('title')),
        h('button', {
          class: `toolbar-toggle ${this.toolbarOpen ? 'active' : ''}`,
          'aria-expanded': this.toolbarOpen,
          onclick: () => { this.toolbarOpen = !this.toolbarOpen; app.draw() },
        }, this.toolbarOpen ? 'Settings ▴' : 'Settings ▾', activeGroups > 0 && h('span', { class: 'count' }, activeGroups))),
      h('div', { class: 'toolbar-extra' },
        h('label', { class: 'tool compact' }, h('span', null, 'Type'), control('type')),
        chest && h('label', { class: 'tool compact' }, h('span', null, 'Size'), control('size')),
        h('span', { class: 'divider' }),
        h('div', { class: 'tool-group' }, groups.map((group) => this.groupButton(group, known))),
        h('span', { class: 'divider' }),
        h('div', { class: 'tool compact' }, h('span', null, 'View as'),
          this.select(DEVICES.map((d) => [d.id, d.label]), this.device, (v) => { this.device = v; app.centerStage = true; this.drawStage() })),
        h('div', { class: 'tool compact' }, h('span', null, 'Scale'),
          h('div', { class: 'tool-row' },
            this.select([['0', 'Fit'], ...SCALES.map((n) => [String(n), `${n}×`])], String(services.scale), (v) => { services.scale = Number(v); app.centerStage = true; this.drawStage() }),
            h('button', { class: 'small', title: 'Put the window back in the middle', onclick: () => { this.pan = { x: 0, y: 0 }; this.place() } }, 'Center')))))
  }

  /** The number of the keys of the group that the menu sets. */
  count(group, known) {
    const menu = this.menu
    if (group.id === 'matrix') {
      const m = menu.matrix
      return m && ((m.cells?.length ?? 0) > 0 || Object.keys(m.map ?? {}).length > 0) ? 1 : 0
    }
    return Object.keys(menu).filter((k) => (group.keys ? group.keys.includes(k) : !known.has(k))).length
  }

  groupButton(group, known) {
    const open = this.popover === group.id
    const count = this.count(group, known)
    const body = group.id === 'pages'
      ? pagesView(this.app)
      : group.id === 'matrix'
        ? matrixEditor(this.app.store, ['matrix'], this.menu.matrix, this.menu)
        : this.app.form.object(this.app.schema.root, [], group.keys ? { only: group.keys } : { hide: [...known] })
    const panel = open && h('div', { class: 'popover', role: 'dialog' },
      h('h3', null, group.label),
      h('p', { class: 'muted' }, group.hint),
      body)
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
    const bad = new Set(problems.filter((p) => p.path?.[0] === 'items').map((p) => p.path[1]))
    if (problems.some((p) => p.path?.[0] === 'pages' && p.path[1] === 'item')) bad.add(TEMPLATE)
    const sample = app.shownMenu().items?.['@element1']
    return h('aside', { class: 'sidebar items' },
      h('div', { class: 'side-head' },
        h('h2', null, 'Items', h('span', { class: 'count' }, ids.length)),
        h('div', { class: 'side-head-actions' },
          h('button', { class: 'primary small', title: 'Add an item to the first free slot', onclick: () => this.add(null, 'items') }, '+ Add'),
          h('button', { class: 'icon small drawer-close', title: 'Close items drawer', 'aria-label': 'Close items drawer', onclick: () => { this.drawerOpen = false; app.draw() } }, '×'))),
      h('p', { class: 'list-hint muted', title: 'Pick an item, then Place it in more slots; or drag it from here onto a slot of the window' },
        'One item can be in many slots, in the menu and in the player\'s inventory under it. Drag one onto a slot, or pick it and place it.'),
      h('div', { class: 'item-rows' },
        menu.pages && this.pagedRow(TEMPLATE, sample ?? menu.pages.item ?? {}, 'Element', `each of ${listedSlots(menu.pages).length} slots · ${menu.pages.catalog?.type ?? 'no catalog'}`, bad.has(TEMPLATE), view),
        menu.pages?.empty && this.pagedRow(EMPTY, menu.pages.empty, 'When there are none', 'drawn when the catalog is empty', false, view),
        ids.length === 0 && !menu.pages && h('p', { class: 'muted pad' }, 'The menu has no items. Add one, or click a slot of the window.'),
        ids.map((id) => this.itemRow(id, view, bad))))
  }

  /** The entry of the list for the item [id]. It can be dragged onto a slot of the window. */
  itemRow(id, view, bad) {
    const { app } = this
    const item = this.menu.items[id]
    const active = id === app.picked.item
    return h('button', { class: `item-row ${active ? 'active' : ''} ${bad.has(id) ? 'bad' : ''}`, 'data-id': id, title: `${id}: ${item.material ?? ''}`,
      draggable: true,
      ondragstart: (e) => { e.dataTransfer.effectAllowed = 'copy'; e.dataTransfer.setData('text/plain', JSON.stringify({ kind: 'item', name: id })) },
      onclick: () => app.pickItem(id) },
    h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio, { contain: true })),
    h('span', { class: 'item-text' }, h('b', null, id), h('small', null, `${placeText(placesOf(this.menu, id))} · ${item.material ?? ''}`)))
  }

  /** The entry of the list for the template of a paged menu, or for its item for no elements: they are items of `pages`, not of `items`. */
  pagedRow(id, item, label, detail, bad, view) {
    return h('button', { class: `item-row paged ${id === this.app.picked.item ? 'active' : ''} ${bad ? 'bad' : ''}`, 'data-id': id, title: detail, onclick: () => this.app.pickItem(id) },
      h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio, { contain: true })),
      h('span', { class: 'item-text' }, h('b', null, label), h('small', null, detail)))
  }

  /** The small bar under the window of a paged menu that turns the example pages, or says that the page of no elements is shown. */
  pagerView() {
    const { app } = this
    const listed = listedSlots(this.menu.pages).length
    if (!(this.menu.pages?.item || this.menu.pages?.matrix) || listed === 0 || app.painting || app.placing) return h('div', { class: 'pager', hidden: true })
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
    return new Set(Object.keys(menu.items ?? {}).flatMap((id) => { const p = placesOf(menu, id); return [...p.items, ...p.matrix] }))
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
    app.picked = { slot: at, item: id, area }
    app.inventory.slot = at
    app.inventory.area = area
    this.drawerOpen = false
    if (typeof window !== 'undefined' && window.innerWidth <= 820) this.mobilePane = 'props'
    app.draw()
  }

  // ---- the item, on the other side

  /** Adds a schema field [key] to the item at [path] using its default or blank value. */
  addItemField(path, key) {
    const field = this.app.schema.field('Item', key)
    if (!field) return
    const blank = field.default !== undefined ? structuredClone(field.default) : this.app.schema.blank(field.type)
    this.app.store.set([...path, key], blank)
  }

  /** Renders the material-aware item form with Safe/Unsafe mode toggle, quick-add meta chips, and preserved-keys banner. */
  itemForm(path, item, alwaysHidden = []) {
    const { app } = this
    const schemaKeys = app.schema.fields('Item').map((f) => f.key)
    const meta = evaluateItemMeta(item, schemaKeys, {
      unsafe: this.unsafeMode,
      resolved: iconSettings.resolved,
      alwaysHidden,
    })
    const matLabel = meta.base.baseMaterial
      ? (meta.base.source !== 'vanilla' ? `${meta.base.source} → ${meta.base.baseMaterial}` : meta.base.baseMaterial)
      : (meta.base.source === 'dynamic' ? 'dynamic material' : `${meta.base.source} item`)

    const modeBar = h('div', { class: `meta-mode-bar ${this.unsafeMode ? 'unsafe' : ''}` },
      h('span', { class: 'meta-mode-info', title: this.unsafeMode ? 'Unsafe mode shows every item property regardless of material.' : `Showing properties applicable to ${matLabel}.` },
        h('span', { class: `mode-dot ${this.unsafeMode ? 'unsafe' : 'safe'}` }),
        h('span', { class: 'meta-mode-label' }, this.unsafeMode ? 'Unsafe · all item meta' : `Safe · ${matLabel}`)),
      h('button', {
        class: `small unsafe-toggle ${this.unsafeMode ? 'on' : ''}`,
        'aria-pressed': this.unsafeMode,
        title: this.unsafeMode
          ? 'Turn off Unsafe mode to only show meta applicable to this item. Existing properties are preserved untouched.'
          : 'Turn on Unsafe mode to show and edit all item meta regardless of material.',
        onclick: () => this.setUnsafeMode(!this.unsafeMode),
      }, this.unsafeMode ? 'Unsafe: ON' : 'Unsafe mode'))

    const quick = !this.unsafeMode && meta.recommended.length > 0 && h('div', { class: 'meta-quick' },
      h('span', { class: 'meta-quick-label muted' }, 'Item meta'),
      h('div', { class: 'meta-quick-chips' }, meta.recommended.map((key) => {
        const docText = app.schema.field('Item', key)?.doc ?? ''
        return h('button', {
          class: 'small meta-chip',
          title: docText,
          onclick: () => this.addItemField(path, key),
        }, `+ ${key}`)
      })))

    const formEl = app.form.object('Item', path, {
      first: ITEM_FIRST,
      hide: meta.hidden,
      group: itemPropertyGroup,
      groupOrder: GROUP_ORDER,
    })

    const preserved = !this.unsafeMode && meta.hiddenPresent.length > 0 && h('div', { class: 'meta-preserved' },
      h('div', { class: 'meta-preserved-text' },
        h('b', null, `Preserved (${meta.hiddenPresent.length})`),
        h('span', { class: 'muted' }, ' — kept untouched on save, not applicable to ', h('code', null, matLabel), ': '),
        meta.hiddenPresent.map((k, i) => [i > 0 && ', ', h('code', null, k)])),
      h('button', { class: 'small', onclick: () => this.setUnsafeMode(true) }, 'Show in Unsafe'))

    return h('div', { class: 'props-body' }, modeBar, quick, formEl, preserved)
  }

  itemPanel() {
    const { app } = this
    const menu = this.menu
    if (isPaged(app.picked.item) && menu.pages) return this.pagedPanel(app.picked.item)
    const id = app.picked.item && menu.items?.[app.picked.item] ? app.picked.item : null
    if (!id) return this.slotPanel()
    const item = menu.items[id]
    const view = viewOf(2)
    const own = app.allProblems().filter((p) => p.path?.[0] === 'items' && p.path[1] === id)
    const name = h('input', { type: 'text', class: 'id-input', value: id, spellcheck: false, 'aria-label': 'Item id',
      oninput: () => name.classList.toggle('bad', Boolean(idProblem(menu, name.value.trim(), id))),
      onchange: () => this.rename(id, name.value.trim(), name),
      onkeydown: (e) => { if (e.key === 'Enter') name.blur() } })
    return h('aside', { class: 'sidebar props' },
      h('div', { class: 'side-head item-head' },
        h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio, { contain: true })),
        h('label', { class: 'tool grow', title: 'The id the item is known by: the matrix map and messages use it' }, h('span', null, 'Item ID'), name),
        h('div', { class: 'item-head-actions' },
          h('button', { class: 'small', title: 'Copy the item', onclick: () => this.duplicate(id) }, 'Copy'),
          h('button', { class: 'danger small', title: 'Remove the item', onclick: () => this.remove(id) }, 'Delete'))),
      own.length > 0 && h('ul', { class: 'item-problems' }, own.slice(0, 6).map((p) => h('li', null, h('code', null, p.path.slice(2).join('.') || 'item'), ' ', p.message))),
      this.placement(id),
      this.itemForm(['items', id], item))
  }

  /**
   * Where the item [id] is drawn: a chip for each slot of the menu and of the player's inventory (click to see it, x to take the item out
   * of it), a note for the slots the menu matrix gives it, and the button that lets slots of the window be clicked to put it in them or take it out.
   */
  placement(id) {
    const { app } = this
    const places = placesOf(this.menu, id)
    const here = (a, slot) => app.picked.slot === slot && (app.picked.area ?? 'items') === a
    const chip = (a, slot) => h('span', { class: `chip ${here(a, slot) ? 'active' : ''}` },
      h('button', { class: 'chip-label', title: 'Show this slot', onclick: () => this.showSlot(a, slot) }, `${a === 'items' ? 'Menu' : 'Inventory'} ${slot}`),
      h('button', { class: 'chip-x remove', title: `Do not draw ${id} in this slot`, 'aria-label': `Remove from slot ${slot}`, onclick: () => this.unplace(id, a, slot) }, '×'))
    const none = places.items.length + places.inventory.length + places.matrix.length === 0
    return h('div', { class: 'placement' },
      h('div', { class: 'placement-head' },
        h('b', null, 'Drawn in'),
        h('button', { class: `small ${app.placing ? 'primary' : ''}`, title: 'Click slots of the window to draw this item in them, or take it out of them. The same item can be in as many as you like.',
          onclick: () => app.place(!app.placing) }, app.placing ? 'Done placing' : 'Place in more slots')),
      h('div', { class: 'chips' },
        none && h('span', { class: 'muted' }, 'No slot yet: the item is never shown.'),
        places.items.map((slot) => chip('items', slot)),
        places.matrix.length > 0 && h('span', { class: 'chip layout', title: 'The cells of the menu matrix that its character stands for. Change them in the Matrix group.' }, `Matrix ${places.matrix.length === 1 ? 'slot' : 'slots'} ${places.matrix.join(', ')}`),
        places.inventory.map((slot) => chip('inventory', slot))))
  }

  /** Shows the slot [slot] of [area] of the window as the picked one, keeping the item that is picked: the chip is one of its places. */
  showSlot(area, slot) {
    const { app } = this
    app.picked = { ...app.picked, slot, area }
    app.inventory.slot = slot
    app.inventory.area = area
    app.draw()
  }

  /** Takes the item [id] out of the slot [slot] of [area]. */
  unplace(id, area, slot) {
    const { app } = this
    app.store.change((draft) => removeClaim(draft, id, area, slot))
    app.draw()
  }

  /** The panel when no item is picked: the slot that is, with what can be put in it. */
  slotPanel() {
    const { app } = this
    const menu = this.menu
    const { slot, area } = app.picked
    const inside = slot !== null && slot !== undefined && slot < (area === 'inventory' ? INVENTORY_SLOTS : slotCount(menu))
    if (!inside) {
      return h('aside', { class: 'sidebar props' },
        h('div', { class: 'empty-state' }, h('h2', null, 'No item picked'),
          h('p', { class: 'muted' }, 'Pick an item in the list or a slot of the window to change it. Drag an item to another slot to move it, hold Alt to draw it in both. Drag one from the list to draw it in a slot.')))
    }
    const rows = Object.keys(menu.items ?? {})
    const view = viewOf(2)
    return h('aside', { class: 'sidebar props' },
      h('div', { class: 'empty-state' },
        h('h2', null, area === 'inventory' ? `Inventory slot ${slot} is empty` : `Slot ${slot} is empty`),
        area === 'inventory' && h('p', { class: 'muted' }, "The player's own item stays there while the menu is open, unless an item is drawn here."),
        h('button', { class: 'primary', onclick: () => this.add(slot, area) }, '+ New item here')),
      rows.length > 0 && h('div', { class: 'reuse' },
        h('div', { class: 'side-head sub' }, h('h2', null, 'Or use one you have')),
        h('div', { class: 'item-rows' }, rows.map((id) => {
          const item = menu.items[id]
          return h('button', { class: 'item-row', title: `Draw ${id} in this slot as well`, onclick: () => app.drop({ kind: 'item', name: id }, { area, slot }, true) },
            h('span', { class: 'item-icon' }, icon(item, view.device, view.ratio)),
            h('span', { class: 'item-text' }, h('b', null, id), h('small', null, `${placeText(placesOf(menu, id))} · ${item.material ?? ''}`)))
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
      this.itemForm(['pages', key], this.menu.pages?.[key] ?? {}, ['slot', 'slots', 'priority']))
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
    const made = freeId(menu.items, `${id}-`)
    const free = typeof menu.items[id].slot === 'number' ? firstFreeSlot(this.taken('items'), slotCount(menu)) : null
    app.store.change((draft) => duplicateItem(draft, id, made, free))
    app.picked = { slot: free, item: made, area: 'items' }
    app.inventory.slot = free
    app.inventory.area = 'items'
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

/** The same for the places of an item (`placesOf`): its slots of the menu, with those of the menu matrix, and its slots of the player's inventory after them. */
export function placeText({ items, matrix, inventory }) {
  const menu = [...new Set([...items, ...(matrix ?? [])])].sort((a, b) => a - b)
  const rows = [...inventory].sort((a, b) => a - b)
  if (menu.length === 0 && rows.length === 0) return 'no slot'
  return [menu.length && slotText(menu), rows.length && `inventory ${rows.length === 1 ? 'slot' : 'slots'} ${rows.length <= 3 ? rows.join(', ') : `×${rows.length}`}`].filter(Boolean).join(' · ')
}
