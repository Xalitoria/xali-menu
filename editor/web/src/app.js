// The page: loads a menu from the server, a link or a file, shows it in tabs, and puts it back.

import { h, fill } from './dom.js'
import { Schema } from './schema.js'
import { Store } from './store.js'
import { normalize } from './normalize.js'
import { Form } from './form.js'
import { Inventory } from './inventory.js'
import { claimsOf, addClaim, removeClaim, moveClaim, placesOf } from './placement.js'
import { check } from './check.js'
import { fromHash, decodeData, Server, readFile, toFile, download } from './source.js'
import { services, forgetCatalog, catalog } from './services.js'
import { materialPicker } from './picker.js'
import { isCustomMaterial } from './items.js'
import { isPaged, listedSlots, toggleSlot, previewMenu, previewPages, TEMPLATE, EMPTY } from './pages.js'
import { geometry } from './window.js'
import { slotCount } from './check.js'
import { settings as iconSettings, potionSpec, potionKey } from './icons.js'
import { settings as assetSettings } from './assets.js'
import { PackPanel } from './panel.js'
import { EditorTab, TYPES, SIZES } from './editorTab.js'
import { RawTab } from './rawTab.js'
import { RelayServer } from './relay.js'
import { registryChoices } from './registry.js'

/** The name that the admin sees on the edits sent through a shared link; asked once for the tab. */
function askName() {
  let name = sessionStorage.getItem('xm-name')
  if (!name) {
    name = (prompt('Your name, for the admin who approves your edits:') ?? '').trim().slice(0, 32) || 'guest'
    sessionStorage.setItem('xm-name', name)
  }
  return name
}

const TABS = ['Editor', 'Raw', 'Pack']

export class App {
  constructor(root, schema) {
    this.root = root
    this.schema = schema
    this.store = new Store({})
    this.store.normalizer = (menu) => normalize(schema, menu)
    this.form = new Form(schema, this.store, {
      material: (path, value) => materialPicker({
        value: value ?? '', placeholder: 'stone, diamond_sword, nexo:ruby, head:Steve',
        // Typing changes the menu without drawing the form again (the box would lose the cursor); a pick or leaving the box does.
        commit: (text, final) => (final ? this.store.set(path, text) : this.store.set(path, text, true)),
      }),
      // The keys of the menu that have a fixed set of values are dropdowns.
      choices: (path) => {
        if (path.length === 1 && path[0] === 'type') return TYPES
        if (path.length === 1 && path[0] === 'size') return SIZES
        // The legend says which item each character of the layout stands for: pick it from the ids of the items.
        if (path.length === 2 && path[0] === 'legend') return Object.keys(this.store.value.items ?? {}).map((id) => [id, id])
        // A paged menu is ordered by one of the values of its catalog.
        if (path.length === 2 && path[0] === 'pages' && path[1] === 'sort') return this.catalogValues().map((name) => [name, name])
        return null
      },
      // A key that takes a value of one of the game's registries (an enchantment, an effect, a dye color...) is a dropdown of them.
      registry: (field) => registryChoices(field),
      extraVariants: (base) => (base === 'Catalog' ? Object.keys(this.serverInfo?.catalogs ?? {}) : []),
    })
    this.tab = 'Editor'
    /** @type {Server|null} */
    this.server = null
    this.name = 'menu'
    this.menuNames = []
    this.serverProblems = []
    /** What is picked: the item (`item`) and the slot of the window it was picked by, which is in [area], the menu's slots (`items`) or the player's inventory (`inventory`). */
    this.picked = { slot: null, item: null, area: 'items' }
    /** The page of the elements the window shows, from 1. */
    this.page = 1
    /** What the server listed for the catalog of the open menu: `{key, elements, total}` once answered, `{key, pending}` while asked, `{key, error}` when refused; null without a server or a paged menu. */
    this.live = null
    this.status = ''
    /** Whether the menu or the server has items of a plugin, which come with a resource pack: the glyph bar is shown then. */
    this.customItems = false
    this.showProblems = false
    /** Whether a click on the window puts the slot among those of the elements of a paged menu (or takes it out), and does not pick the item in it. */
    this.painting = false
    /** Whether a click on the window draws the picked item in that slot (or takes it out), and does not pick the slot. */
    this.placing = false
    this.inventory = new Inventory(this.store, {
      select: (slot, entries, area) => this.pickSlot(slot, entries, area),
      drop: (source, target, copy) => this.drop(source, target, copy),
    })
    this.pack = new PackPanel(this)
    this.editor = new EditorTab(this)
    this.raw = new RawTab(this)
    this.asked = new Set()
    this.store.onChange((quiet) => {
      this.refreshHeader()
      // Typing in a field: the window is drawn again a moment later, and what the server knows of the new text (the color of a potion) is asked once typing stops.
      if (quiet) { if (this.tab === 'Editor') this.editor.refresh(); this.fetchElements(); this.resolveSoon(); return }
      // The menu was changed by something other than the text, so a draft of the text is out of date (applying it is what sets the menu, and clears it).
      if (!this.raw.applying) this.raw.discard()
      this.draw()
      this.resolveItems()
      this.findCustomItems()
      this.fetchElements()
    })
    window.addEventListener('keydown', (e) => this.keys(e))
    // A click outside the panel of a group of properties, or of the problems, closes it.
    document.addEventListener('click', (e) => {
      const inside = (selector) => e.target.closest?.(selector)
      if (this.editor.popover && !inside('.pop-wrap')) { this.editor.popover = null; this.draw() }
      else if (this.showProblems && !inside('.problems-wrap')) { this.showProblems = false; this.drawProblems() }
    })
    window.addEventListener('beforeunload', (e) => { if (this.store.dirty) e.preventDefault() })
    this.build()
  }

  /** Reads the address and loads what it points at. */
  async start() {
    const hash = fromHash(location.hash)
    try {
      if ((hash.server && hash.token) || (hash.relay && hash.session && hash.key)) {
        this.server = hash.relay ? new RelayServer(hash.relay, hash.session, hash.key, askName()) : new Server(hash.server, hash.token)
        assetSettings.skinProxy = (url) => this.server.skin(url)
        assetSettings.gameFile = (file) => this.server.gameFile(file)
        services.server = this.server
        forgetCatalog()
        const info = await this.server.info()
        this.menuNames = info.menus
        this.serverInfo = info
        const name = hash.menu && info.menus.includes(hash.menu) ? hash.menu : info.menus[0]
        if (name) await this.open(name)
        this.pack.discover()
      } else if (hash.data) {
        const data = await decodeData(hash.data)
        this.name = hash.name ?? 'menu'
        this.store.load(data.menu ?? data)
        this.setStatus('Loaded from the link. Download the file when done and put it in plugins/XaliMenu/menus/.')
      }
    } catch (error) {
      this.setStatus(error.message, true)
    }
    this.build()
    this.draw()
  }

  async open(name) {
    if (this.store.dirty && !confirm('Discard the changes to the menu that is open?')) return
    this.name = name
    this.centerStage = true
    this.raw.discard()
    this.store.load(await this.server.menu(name))
    this.serverProblems = []
    this.picked = { slot: null, item: null, area: 'items' }
    this.placing = false
    this.setStatus(`Opened ${name}.`)
    this.draw()
    this.resolveItems()
    this.findCustomItems()
    this.fetchElements()
  }

  /** Looks for items of plugins in the menu, and on the server: they come with a resource pack, so the glyph bar is shown for them. */
  async findCustomItems() {
    let found = this.allItems().some((item) => isCustomMaterial(item.material))
    if (!found && this.server) {
      try { found = (await catalog()).some((entry) => entry.kind === 'custom') } catch { /* the server cannot list them */ }
    }
    if (found === this.customItems) return
    this.customItems = found
    if (this.tab === 'Editor') this.draw()
  }

  /** The items of the menu. */
  allItems() {
    return Object.values(this.store.value.items ?? {})
  }

  /**
   * Picks the slot [slot] of [area] that was clicked in the window, and the item that is drawn in it ([names] says which, the one drawn first at the
   * front). While slots are being picked for the elements of a paged menu, or for the picked item, it does that instead.
   */
  pickSlot(slot, names, area = 'items') {
    const menu = this.store.value
    if (this.painting) {
      // The player's inventory, past the slots of the menu, cannot hold an element.
      if (area === 'items' && menu.pages && slot < slotCount(menu)) this.store.set(['pages'], toggleSlot(menu.pages, slot, geometry(menu).columns))
      return
    }
    if (this.placing) {
      this.togglePlace(slot, area)
      return
    }
    const name = names[0] ?? null
    // The elements the preview draws are all the one template.
    const item = name === EMPTY ? EMPTY : isPaged(name) ? TEMPLATE : name
    this.picked = { slot, item, area }
    this.draw()
  }

  /** Draws the picked item in [slot] of [area] if it is not there, takes it out if it is. */
  togglePlace(slot, area) {
    const { item: id } = this.picked
    const menu = this.store.value
    if (!id || isPaged(id) || !menu.items?.[id]) return
    // Past the slots of the menu is the player's inventory, which the cells of the window draw as theirs.
    if (area === 'items' && slot >= slotCount(menu)) return
    const here = claimsOf(menu.items[id], area).includes(slot)
    this.store.change((draft) => (here ? removeClaim(draft, id, area, slot) : addClaim(draft, id, area, slot)))
    this.draw()
  }

  /** Starts or ends picking the slots the picked item is drawn in. */
  place(on) {
    this.placing = on && Boolean(this.picked.item) && !isPaged(this.picked.item)
    this.painting = false
    this.editor.popover = null
    this.draw()
  }

  /**
   * Draws the item [source] (`{kind, name}`; for kind `slot` also the `area` and `slot` it was dragged from) in the slot [target] (`{area, slot}`): it
   * moves from its slot, or, when [copy] is true or it came from the list of items, it is drawn there as well.
   */
  drop(source, target, copy = false) {
    const menu = this.store.value
    const id = source.name
    if (!menu.items?.[id]) return
    if (target.area === 'items' && target.slot >= slotCount(menu)) return
    this.store.change((draft) => (source.kind === 'slot'
      ? moveClaim(draft, id, { area: source.area, slot: source.slot }, target, copy)
      : addClaim(draft, id, target.area, target.slot)))
    this.picked = { slot: target.slot, item: id, area: target.area }
    this.inventory.slot = target.slot
    this.inventory.area = target.area
    this.draw()
  }

  /** The values that the elements of the catalog of a paged menu hold (name to an example), from the schema and from the server's plugins. */
  catalogFields(type = this.store.value.pages?.catalog?.type) {
    return { ...(this.schema.data.catalogs?.[type] ?? {}), ...(this.serverInfo?.catalogs?.[type]?.values ?? {}) }
  }

  /** The keys the settings of the catalog take (key to an example), where the plugin that added it said; empty for the plugin's own, which the schema describes. */
  catalogSettings(type = this.store.value.pages?.catalog?.type) {
    return this.serverInfo?.catalogs?.[type]?.settings ?? {}
  }

  catalogValues() { return [...Object.keys(this.catalogFields()), 'index'] }

  /** The names of the catalogs there are: those of the plugin, and those of the plugins of the server. */
  catalogTypes() {
    return [...new Set([...this.schema.variants('Catalog'), ...Object.keys(this.serverInfo?.catalogs ?? {})])].sort()
  }

  /** The menu as the window draws it: for a paged menu, the page that was turned to, or the page of no elements while the item for that is picked. */
  shownMenu() { return previewMenu(this.store.value, this.catalogFields(), { page: this.page, empty: this.picked.item === EMPTY, elements: this.liveElements() }) }

  /** The elements the server listed for the catalog of the menu as it is now, or null when there are none to show (no server, not answered yet, none listed). */
  liveElements() {
    const live = this.live
    return live && live.key === this.liveKey() && live.elements?.length ? live.elements : null
  }

  /** What the listing depends on: the catalog and the order. A change of either asks the server again. */
  liveKey() {
    const pages = this.store.value.pages
    return pages?.catalog?.type ? JSON.stringify([pages.catalog, pages.sort ?? null, Boolean(pages.descending)]) : null
  }

  /** The pages the preview can turn through: those of the server's elements when there are some, otherwise the example pages. */
  previewPages() { return previewPages(Math.max(1, listedSlots(this.store.value.pages).length), this.liveElements()) }

  /** Turns the preview of a paged menu to the page [delta] away. */
  turnPage(delta) {
    this.page = Math.min(Math.max(1, this.page + delta), this.previewPages())
    this.editor.drawStage()
  }

  /** Asks the server what the catalog lists now, a little after the last change to it, and draws the window again when the answer comes. */
  fetchElements() {
    clearTimeout(this.fetching)
    const key = this.liveKey()
    if (!this.server || key === null) { if (this.live) { this.live = null; this.editor.drawStage() } return }
    if (this.live?.key === key) return
    this.fetching = setTimeout(async () => {
      this.live = { key, pending: true }
      this.page = 1
      try {
        const { elements, total } = await this.server.elements(this.store.value.pages.catalog, this.store.value.pages.sort, Boolean(this.store.value.pages.descending))
        if (this.live?.key !== key) return
        this.live = { key, elements, total }
      } catch (error) {
        if (this.live?.key !== key) return
        this.live = { key, error: error.message }
      }
      this.page = Math.min(this.page, this.previewPages())
      if (this.tab === 'Editor') this.editor.drawStage()
    }, 300)
  }

  /** Starts or ends picking the slots of the elements in the window. */
  paint(on) {
    this.painting = on
    if (on) this.placing = false
    this.editor.popover = null
    this.draw()
  }

  /** Picks an item of the list, and its first slot: one of the menu's, else one of the player's inventory. */
  pickItem(id) {
    const menu = this.store.value
    let slots
    let area = 'items'
    if (isPaged(id)) slots = listedSlots(menu.pages).slice(0, 1)
    else {
      const places = placesOf(menu, id)
      slots = [...new Set([...places.items, ...places.layout])].sort((a, b) => a - b)
      if (slots.length === 0) { slots = [...places.inventory].sort((a, b) => a - b); area = 'inventory' }
    }
    this.picked = { slot: slots[0] ?? null, item: id, area }
    this.placing = false
    this.inventory.slot = this.picked.slot
    this.inventory.area = area
    this.draw()
  }

  /** [resolveItems] once the typing has paused; the pictures are then drawn again without redrawing the form, which would take the cursor out of the box. */
  resolveSoon() {
    clearTimeout(this.resolving)
    this.resolving = setTimeout(() => this.resolveItems(true), 500)
  }

  /** Asks the server what the items of the open menu are (custom items, and how much each wears), once for each material. */
  async resolveItems(quiet = false) {
    if (!this.server) return
    const items = this.allItems()
    const materials = [...new Set(items.map((i) => i.material).filter((m) => typeof m === 'string' && m && !m.includes('%') && !this.asked.has(m)))]
    const potions = [...new Map(items.map(potionSpec).filter((s) => s && !this.asked.has(potionKey(s))).map((s) => [potionKey(s), s])).values()]
    if (materials.length === 0 && potions.length === 0) return
    materials.forEach((m) => this.asked.add(m))
    potions.forEach((s) => this.asked.add(potionKey(s)))
    try {
      if (materials.length) for (const [material, data] of Object.entries(await this.server.resolve(materials))) iconSettings.resolved.set(material, data)
      if (potions.length) (await this.server.colors(potions)).forEach((color, i) => { if (color !== null) iconSettings.potionColors.set(potionKey(potions[i]), color) })
      if (quiet && this.tab === 'Editor') this.editor.refresh()
      else this.draw()
    } catch { /* the server may not know; the items are then drawn from what the menu says */ }
  }

  setStatus(text, bad = false) {
    this.status = text
    this.statusBad = bad
    this.refreshHeader()
  }

  // ---- layout

  build() {
    this.menuSelect = h('select', { title: 'Menu', onchange: () => this.open(this.menuSelect.value) })
    this.statusLine = h('span', { class: 'status' })
    this.buttons = {
      undo: h('button', { onclick: () => this.store.undo(), title: 'Undo (Ctrl+Z)' }, '↶'),
      redo: h('button', { onclick: () => this.store.redo(), title: 'Redo (Ctrl+Y)' }, '↷'),
      open: h('button', { onclick: () => this.pickFile() }, 'Open file'),
      download: h('button', { onclick: () => this.download() }, 'Download .conf'),
      save: h('button', { class: 'primary', onclick: () => this.save() }, fromHash(location.hash).relay ? 'Send for approval' : 'Save to server'),
    }
    this.tabs = h('nav', { class: 'tabs' }, TABS.map((t) => h('button', { 'data-tab': t, onclick: () => this.goTo(t) }, t)))
    this.problemsButton = h('button', { class: 'problems-button', onclick: () => { this.showProblems = !this.showProblems; this.drawProblems() } })
    this.problemsPanel = h('div', { class: 'problems-pop', hidden: true })
    this.tabs.append(h('span', { class: 'spacer' }), h('div', { class: 'problems-wrap' }, this.problemsButton, this.problemsPanel))
    this.view = h('section', { class: 'view' })
    fill(this.root,
      h('header', null, h('span', { class: 'brand' }, 'XaliMenu editor'), this.menuSelect, this.statusLine,
        h('span', { class: 'spacer' }), this.buttons.undo, this.buttons.redo, this.buttons.open, this.buttons.download, this.buttons.save),
      this.tabs,
      h('main', null, this.view))
    this.refreshHeader()
  }

  /** Switches tab. Leaving the text with changes that were not applied asks what to do with them. */
  goTo(tab) {
    if (tab === this.tab) return
    if (this.tab === 'Raw' && this.raw.pending) {
      if (confirm('The text has changes that were not applied. OK applies them to the editor, Cancel throws them away.')) { if (!this.applyRaw()) return }
      else this.raw.discard()
    }
    this.raw.note = null
    this.tab = tab
    this.draw()
  }

  /** Reads the text of the Raw tab into the editor. Returns false when it cannot be read. */
  applyRaw() {
    this.raw.applying = true
    try { return this.raw.apply() } finally { this.raw.applying = false; this.draw() }
  }

  refreshHeader() {
    fill(this.menuSelect, this.menuNames.map((n) => h('option', { value: n, selected: n === this.name }, n)))
    this.menuSelect.hidden = this.menuNames.length === 0
    this.statusLine.textContent = `${this.store.dirty ? '● ' : ''}${this.status}`
    this.statusLine.className = `status ${this.statusBad ? 'bad' : ''}`
    this.buttons.save.hidden = !this.server || this.serverInfo?.readOnly === true
    this.buttons.undo.disabled = this.store.undoStack.length === 0
    this.buttons.redo.disabled = this.store.redoStack.length === 0
  }

  draw() {
    if (!this.store.value.pages) this.painting = false
    if (this.placing && !this.store.value.items?.[this.picked.item]) this.placing = false
    for (const button of this.tabs.querySelectorAll('button[data-tab]')) button.classList.toggle('active', button.dataset.tab === this.tab)
    const kept = this.scrolls()
    const content = { Editor: () => this.editorTab(), Raw: () => this.raw.view(), Pack: () => this.pack.view() }[this.tab]()
    // The editor fills the view and keeps its own scroll; the other tabs are pages that scroll.
    this.view.classList.toggle('fixed', this.tab === 'Editor' && Object.keys(this.store.value).length > 0)
    fill(this.view, content)
    this.restoreScrolls(kept)
    if (this.tab === 'Editor') this.editor.attached()
    this.drawProblems()
    this.refreshHeader()
  }

  /** The places that scroll and are rebuilt by a redraw: a change must not throw the reader back to the top of them. */
  static SCROLLERS = ['.sidebar.props', '.sidebar.items .item-rows', '.popover']

  /** Where each of them is scrolled to now. */
  scrolls() {
    return App.SCROLLERS.map((selector) => this.view.querySelector(selector)?.scrollTop ?? 0)
  }

  /** Puts them back, unless the view is now about something else (another item, group or tab), which starts at the top again. */
  restoreScrolls(tops) {
    const place = `${this.tab}|${this.picked.item}|${this.editor.popover}`
    if (place === this.drawnPlace) App.SCROLLERS.forEach((selector, i) => { const element = this.view.querySelector(selector); if (element && tops[i]) element.scrollTop = tops[i] })
    this.drawnPlace = place
  }

  // ---- tabs

  editorTab() {
    return Object.keys(this.store.value).length === 0 ? this.welcome() : this.editor.view()
  }

  welcome() {
    return h('div', { class: 'welcome' },
      h('h1', null, 'Edit XaliMenu menus'),
      h('p', null, 'Run ', h('code', null, '/xmenu edit'), ' in the game and open the link it prints, or open a menu file from ', h('code', null, 'plugins/XaliMenu/menus/'), '.'),
      h('div', { class: 'row-buttons' },
        h('button', { class: 'primary', onclick: () => this.pickFile() }, 'Open a menu file'),
        h('button', { onclick: () => { this.name = 'new'; this.store.load({ title: 'New menu', size: 27, items: {} }); this.draw() } }, 'Start a new menu')),
      h('p', { class: 'muted' }, 'The file stays in your browser; nothing is uploaded unless you save to a server.'))
  }

  // ---- problems

  allProblems() {
    const own = check(this.schema, this.store.value).map((p) => ({ path: p.path, where: p.path.join('.'), message: p.message }))
    const server = this.serverProblems.map((m) => ({ path: null, where: 'server', message: m }))
    return [...own, ...server]
  }

  /** Goes to what a problem is about: the item, or the group of properties of the menu that holds the key. */
  showProblem(problem) {
    const [first, second] = problem.path ?? []
    this.showProblems = false
    this.tab = 'Editor'
    if (first === 'items' && second && this.store.value.items?.[second]) this.pickItem(second)
    else if (first === 'pages' && second === 'item') this.pickItem(TEMPLATE)
    else {
      this.editor.popover = this.editor.groupOf(first)
      this.draw()
    }
  }

  drawProblems() {
    const problems = Object.keys(this.store.value).length ? this.allProblems() : []
    this.problemsButton.textContent = problems.length ? `⚠ ${problems.length} problem${problems.length === 1 ? '' : 's'}` : '✓ No problems'
    this.problemsButton.className = `problems-button ${problems.length ? 'has' : ''}`
    this.problemsPanel.hidden = !this.showProblems || problems.length === 0
    fill(this.problemsPanel,
      h('ul', null, problems.slice(0, 200).map((p) => h('li', null,
        h('button', { class: 'problem', onclick: () => this.showProblem(p) }, h('code', null, p.where), ' ', p.message)))))
  }

  // ---- files and the server

  pickFile() {
    const input = h('input', { type: 'file', accept: '.conf,.json,.hocon' })
    input.onchange = async () => {
      const file = input.files[0]
      if (!file) return
      try {
        this.store.load(readFile(file.name, await file.text()))
        this.name = file.name.replace(/\.(conf|json|hocon)$/, '')
        this.setStatus(`Opened ${file.name}.`)
        this.draw()
      } catch (error) { this.setStatus(`${file.name}: ${error.message}`, true) }
    }
    input.click()
  }

  download() {
    const file = toFile(this.name, this.store.value)
    download(file.name, file.text)
    this.store.markSaved()
    this.setStatus(`Downloaded ${file.name}. Put it in plugins/XaliMenu/menus/ and run /xmenu reload ${this.name}.`)
  }

  async save() {
    if (this.serverInfo?.readOnly) { this.setStatus('This link is read-only: download the menu instead.', true); return }
    try {
      this.serverProblems = await this.server.save(this.name, this.store.value)
      this.store.markSaved()
      const note = this.server.held ? `Sent ${this.name} to the admin of the server, who has to approve it in game (/xmenu edits).` : `Saved ${this.name}.`
      this.setStatus(this.serverProblems.length ? note.replace(/\.$/, ', with problems.') : note)
    } catch (error) { this.setStatus(error.message, true) }
    this.draw()
  }

  keys(event) {
    if (event.key === 'Escape' && (this.editor.popover || this.showProblems || this.painting || this.placing)) {
      this.editor.popover = null
      this.showProblems = false
      this.painting = false
      this.placing = false
      this.draw()
      return
    }
    if (!(event.ctrlKey || event.metaKey)) return
    const key = event.key.toLowerCase()
    // In the text, undo and redo are the text's own; only the save is the editor's.
    const inText = event.target.matches?.('textarea.raw')
    if (key === 's') {
      event.preventDefault()
      // The text is read into the editor first, then the menu is saved: what is on the screen is what is saved.
      if (this.tab === 'Raw' && !this.applyRaw()) return
      this.server ? this.save() : this.download()
    } else if (inText) return
    else if (key === 'z' && !event.shiftKey) { event.preventDefault(); this.store.undo() }
    else if (key === 'y' || (key === 'z' && event.shiftKey)) { event.preventDefault(); this.store.redo() }
  }
}

export async function boot() {
  const root = document.getElementById('app')
  const schema = await Schema.load()
  const app = new App(root, schema)
  window.editor = app
  await app.start()
}
