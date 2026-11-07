// The page: loads a menu from the server, a link or a file, shows it in tabs, and puts it back.

import { h, fill } from './dom.js'
import { Schema } from './schema.js'
import { Store } from './store.js'
import { Form } from './form.js'
import { Inventory, bySlot } from './inventory.js'
import { check } from './check.js'
import { fromHash, decodeData, Server, readFile, toFile, download } from './source.js'
import { parse } from './hocon.js'
import { write } from './hoconWriter.js'
import { render as renderText } from './mctext.js'
import { services, forgetCatalog } from './services.js'
import { materialPicker } from './picker.js'
import { SCALES } from './scale.js'
import { settings as iconSettings, potionSpec, potionKey } from './icons.js'
import { settings as assetSettings } from './assets.js'
import { PackPanel } from './panel.js'

const TABS = ['Menu', 'Items', 'Raw', 'Pack']
const ITEM_FIRST = ['material', 'name', 'lore', 'amount', 'slot', 'slots', 'view', 'click']

export class App {
  constructor(root, schema) {
    this.root = root
    this.schema = schema
    this.store = new Store({})
    this.form = new Form(schema, this.store, {
      material: (path, value) => materialPicker({
        value: value ?? '', placeholder: 'stone, diamond_sword, nexo:ruby, head:Notch',
        // Typing changes the menu without drawing the form again (the box would lose the cursor); a pick or leaving the box does.
        commit: (text, final) => (final ? this.store.set(path, text) : this.store.set(path, text, true)),
      }),
    })
    this.tab = 'Menu'
    /** @type {Server|null} */
    this.server = null
    this.name = 'menu'
    this.menuNames = []
    this.serverProblems = []
    this.picked = { slot: null, item: null }
    this.status = ''
    this.inventory = new Inventory(this.store, {
      select: (slot, names) => { this.picked = { slot, item: names[0] ?? null }; this.draw() },
    })
    this.pack = new PackPanel(this)
    this.asked = new Set()
    this.store.onChange((quiet) => { this.refreshHeader(); if (!quiet) { this.draw(); this.resolveItems() } })
    window.addEventListener('keydown', (e) => this.keys(e))
    // Zooming the browser changes the pixel ratio and fires a resize; the window is drawn again at a whole number of screen pixels per pixel of the game.
    let timer = 0
    window.addEventListener('resize', () => { clearTimeout(timer); timer = setTimeout(() => { if (this.tab === 'Items' || this.tab === 'Pack') this.draw() }, 150) })
    window.addEventListener('beforeunload', (e) => { if (this.store.dirty) e.preventDefault() })
    this.build()
  }

  /** Reads the address and loads what it points at. */
  async start() {
    const hash = fromHash(location.hash)
    try {
      if (hash.server && hash.token) {
        this.server = new Server(hash.server, hash.token)
        assetSettings.skinProxy = (url) => this.server.skin(url)
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
    this.store.load(await this.server.menu(name))
    this.serverProblems = []
    this.picked = { slot: null, item: null }
    this.setStatus(`Opened ${name}.`)
    this.draw()
    this.resolveItems()
  }

  /** Asks the server what the items of the open menu are (custom items, and how much each wears), once for each material. */
  async resolveItems() {
    if (!this.server) return
    const items = Object.values(this.store.value.items ?? {})
    const materials = [...new Set(items.map((i) => i.material).filter((m) => typeof m === 'string' && m && !m.includes('%') && !this.asked.has(m)))]
    const potions = [...new Map(items.map(potionSpec).filter((s) => s && !this.asked.has(potionKey(s))).map((s) => [potionKey(s), s])).values()]
    if (materials.length === 0 && potions.length === 0) return
    materials.forEach((m) => this.asked.add(m))
    potions.forEach((s) => this.asked.add(potionKey(s)))
    try {
      if (materials.length) for (const [material, data] of Object.entries(await this.server.resolve(materials))) iconSettings.resolved.set(material, data)
      if (potions.length) (await this.server.colors(potions)).forEach((color, i) => { if (color !== null) iconSettings.potionColors.set(potionKey(potions[i]), color) })
      this.draw()
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
      save: h('button', { class: 'primary', onclick: () => this.save() }, 'Save to server'),
    }
    this.tabs = h('nav', { class: 'tabs' }, TABS.map((t) => h('button', { 'data-tab': t, onclick: () => { this.tab = t; this.draw() } }, t)))
    this.view = h('section', { class: 'view' })
    this.problemsPanel = h('aside', { class: 'problems' })
    fill(this.root,
      h('header', null, h('span', { class: 'brand' }, 'XaliMenu editor'), this.menuSelect, this.statusLine,
        h('span', { class: 'spacer' }), this.buttons.undo, this.buttons.redo, this.buttons.open, this.buttons.download, this.buttons.save),
      this.tabs,
      h('main', null, this.view, this.problemsPanel))
    this.refreshHeader()
  }

  refreshHeader() {
    fill(this.menuSelect, this.menuNames.map((n) => h('option', { value: n, selected: n === this.name }, n)))
    this.menuSelect.hidden = this.menuNames.length === 0
    this.statusLine.textContent = `${this.store.dirty ? '● ' : ''}${this.status}`
    this.statusLine.className = `status ${this.statusBad ? 'bad' : ''}`
    this.buttons.save.hidden = !this.server
    this.buttons.undo.disabled = this.store.undoStack.length === 0
    this.buttons.redo.disabled = this.store.redoStack.length === 0
  }

  draw() {
    // The window is wider than the space when it has room around it: keep where the person scrolled to, or start in the middle of it.
    const before = this.view.querySelector('.stage')
    if (before) this.stageScroll = [before.scrollLeft, before.scrollTop]
    for (const button of this.tabs.children) button.classList.toggle('active', button.dataset.tab === this.tab)
    const content = { Menu: () => this.menuTab(), Items: () => this.itemsTab(), Raw: () => this.rawTab(), Pack: () => this.pack.view() }[this.tab]()
    fill(this.view, content)
    const stage = this.view.querySelector('.stage')
    if (stage) {
      const at = this.stageScroll
      if (at && !this.centerStage) { stage.scrollLeft = at[0]; stage.scrollTop = at[1] }
      else { stage.scrollLeft = (stage.scrollWidth - stage.clientWidth) / 2; stage.scrollTop = (stage.scrollHeight - stage.clientHeight) / 2 }
      this.centerStage = false
    }
    this.drawProblems()
    this.refreshHeader()
  }

  // ---- tabs

  menuTab() {
    if (Object.keys(this.store.value).length === 0) return this.welcome()
    return h('div', { class: 'page' },
      this.form.object(this.schema.root, [], { hide: ['items'], first: ['title', 'size', 'type', 'commands', 'args'] }))
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

  itemsTab() {
    const menu = this.store.value
    if (Object.keys(menu).length === 0) return this.welcome()
    // The space for the window: what the page gives the view, less the column of the item.
    this.inventory.draw(Math.max(300, (this.view.clientWidth || 900) - 380))
    const names = Object.keys(menu.items ?? {})
    const claimed = this.picked.slot !== null ? (bySlot(menu).get(this.picked.slot) ?? []) : []
    const item = this.picked.item && menu.items?.[this.picked.item] ? this.picked.item : null
    return h('div', { class: 'items-tab' },
      h('div', { class: 'left' },
        h('div', { class: 'toolbar' },
          h('label', null, 'GUI scale ', this.choice([['0', 'Fit'], ...SCALES.map((n) => [String(n), String(n)])], String(services.scale), (v) => { services.scale = Number(v); this.centerStage = true; this.draw() })),
          h('label', null, 'Room around ', this.choice([['0', 'None'], ['32', 'Small'], ['64', 'Medium'], ['128', 'Large'], ['256', 'Huge']], String(services.room), (v) => { services.room = Number(v); this.centerStage = true; this.draw() }))),
        h('div', { class: 'stage' }, this.inventory.root),
        h('div', { class: 'picker' },
          this.picked.slot !== null && h('span', null, `Slot ${this.picked.slot}: `),
          claimed.map((n) => h('button', { class: n === item ? 'active' : '', onclick: () => { this.picked.item = n; this.draw() } }, n)),
          this.picked.slot !== null && h('button', { onclick: () => this.addItem() }, '+ Item here')),
        names.length > 0 && h('div', { class: 'picker' }, h('span', null, 'All items: '),
          names.map((n) => h('button', { class: n === item ? 'active' : '', onclick: () => { this.picked.item = n; this.draw() } }, n)))),
      h('div', { class: 'right' }, item
        ? [h('h2', null, item, h('button', { class: 'danger', onclick: () => this.removeItem(item) }, 'Delete item')),
           this.form.object('Item', ['items', item], { first: ITEM_FIRST })]
        : h('p', { class: 'muted' }, 'Pick a slot to see what it holds, or add an item to it.')))
  }

  /** A small select with [options] as `[value, label]`; [change] gets the value picked. */
  choice(options, current, change) {
    const select = h('select', { onchange: () => change(select.value) }, options.map(([value, label]) => h('option', { value, selected: value === current }, label)))
    return select
  }

  addItem() {
    const items = this.store.value.items ?? {}
    let n = 1
    while (`item${n}` in items) n++
    const name = `item${n}`
    this.store.set(['items', name], { material: 'stone', slot: this.picked.slot })
    this.picked.item = name
    this.draw()
  }

  removeItem(name) {
    this.store.remove(['items', name])
    this.picked.item = null
  }

  rawTab() {
    const text = write(this.store.value)
    const area = h('textarea', { class: 'raw', spellcheck: false, value: text })
    const message = h('span', { class: 'muted' }, 'Edit the text and press Apply.')
    return h('div', { class: 'page' },
      h('div', { class: 'row-buttons' },
        h('button', { class: 'primary', onclick: () => {
          try { this.store.set([], parse(area.value)); message.textContent = 'Applied.' } catch (e) { message.textContent = e.message; message.className = 'bad' }
        } }, 'Apply'), message),
      area)
  }

  // ---- problems

  allProblems() {
    const own = check(this.schema, this.store.value).map((p) => ({ where: p.path.join('.'), message: p.message }))
    const server = this.serverProblems.map((m) => ({ where: 'server', message: m }))
    return [...own, ...server]
  }

  drawProblems() {
    const problems = Object.keys(this.store.value).length ? this.allProblems() : []
    fill(this.problemsPanel,
      h('h3', null, problems.length ? `${problems.length} problem${problems.length === 1 ? '' : 's'}` : 'No problems'),
      h('ul', null, problems.slice(0, 200).map((p) => h('li', null, h('code', null, p.where), ' ', p.message))))
  }

  // ---- title

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
    try {
      this.serverProblems = await this.server.save(this.name, this.store.value)
      this.store.markSaved()
      this.setStatus(this.serverProblems.length ? `Saved ${this.name}, with problems.` : `Saved ${this.name}.`)
    } catch (error) { this.setStatus(error.message, true) }
    this.draw()
  }

  keys(event) {
    if (!(event.ctrlKey || event.metaKey) || event.target.matches?.('textarea.raw')) return
    const key = event.key.toLowerCase()
    if (key === 'z' && !event.shiftKey) { event.preventDefault(); this.store.undo() }
    else if (key === 'y' || (key === 'z' && event.shiftKey)) { event.preventDefault(); this.store.redo() }
    else if (key === 's') { event.preventDefault(); this.server ? this.save() : this.download() }
  }
}

export async function boot() {
  const root = document.getElementById('app')
  const schema = await Schema.load()
  const app = new App(root, schema)
  window.editor = app
  await app.start()
}
