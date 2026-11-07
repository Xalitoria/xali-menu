// The resource pack: loading it (from the server, a file or an address), what its fonts have, and the "Pack" tab. Titles are drawn with the
// pack's glyphs through `services`; the glyph bar of the editor screen picks and moves them.

import { h, fill } from './dom.js'
import { Pack } from './pack.js'
import { DEFAULT_FONT } from './fonts.js'
import { services, configure } from './services.js'

export class PackPanel {
  /** @param {import('./app.js').App} app */
  constructor(app) {
    this.app = app
    /** @type {Pack|null} */
    this.pack = null
    /** Glyphs the server's plugins define: `{name, char, font, plugin}`. */
    this.catalog = []
    this.fontKey = DEFAULT_FONT
    /** The glyph picked in the glyph bar, the font it is in. */
    this.glyph = null
    this.glyphFont = null
    this.message = ''
  }

  // ---- loading

  async setPack(blob, label) {
    this.message = 'Reading the pack…'
    this.app.draw()
    try {
      this.pack = await Pack.load(blob, label)
      configure(this.pack)
      services.text.preprocess = (text) => this.expand(text)
      await this.glyphFonts()
      this.fontKey = this.pack.fontKeys().includes(DEFAULT_FONT) ? DEFAULT_FONT : (this.pack.fontKeys()[0] ?? DEFAULT_FONT)
      const summary = await this.pack.summary()
      this.message = `${label}: ${summary.namespaces.length} namespaces, ${summary.fonts} fonts, ${summary.bitmaps} picture providers.`
    } catch (error) {
      this.pack = null
      configure(null)
      this.message = `The pack could not be read: ${error.message}`
    }
    this.app.draw()
  }

  /** Asks the server for its pack, its glyphs and what its custom items are. */
  async discover() {
    const server = this.app.server
    if (!server) return
    try { this.catalog = await server.glyphs() } catch { this.catalog = [] }
    try {
      const blob = await server.pack()
      if (blob) await this.setPack(blob, 'the server pack')
    } catch { /* the server has no pack */ }
  }

  // ---- names of glyphs

  /** Every font of the pack, loaded once: key to font. @returns {Promise<Map<string, any>>} */
  fontMap() {
    if (!this.pack) return Promise.resolve(new Map())
    if (this.fontsOf?.pack !== this.pack) {
      const pack = this.pack
      this.fontsOf = { pack, map: Promise.all(pack.fontKeys().map(async (k) => [k, await pack.font(k)])).then((loaded) => new Map(loaded)) }
    }
    return this.fontsOf.map
  }

  /** Which font has each picture character, found once per pack: plugin glyphs are usually in a font of their own. @returns {Promise<Map<string, string>>} */
  glyphFonts() {
    if (!this.pack) return Promise.resolve(new Map())
    if (this.fontIndex?.pack !== this.pack) {
      const pack = this.pack
      this.fontIndex = {
        pack,
        map: (async () => {
          const found = new Map()
          // The default font first, so a character that is in both is drawn the way the game would.
          for (const k of [...pack.fontKeys()].sort((a, b) => (b === DEFAULT_FONT) - (a === DEFAULT_FONT))) {
            for (const character of (await pack.font(k)).glyphs.keys()) if (!found.has(character)) found.set(character, k)
          }
          this.fontIndex.found = found
          return found
        })(),
      }
    }
    return this.fontIndex.map
  }

  /** Turns the names plugins define (`<glyph:name>`, `:name:`, `%img_name%`) into characters; the text draws what this gives. */
  expand(text) {
    let result = text
    for (const glyph of this.catalog) {
      const font = glyph.font || this.fontIndex?.found?.get(glyph.char)
      const wrapped = font && font !== DEFAULT_FONT ? `<font:${font}>${glyph.char}</font>` : glyph.char
      for (const form of [`<glyph:${glyph.name}>`, `:${glyph.name}:`, `%img_${glyph.name}%`]) result = result.split(form).join(wrapped)
    }
    return result
  }

  // ---- the tab

  view() {
    return h('div', { class: 'page pack-tab' },
      this.loader(),
      this.pack ? h('p', { class: 'muted' }, 'The glyphs of the pack are in the bar under the window of the Editor tab: pick one there and move it into place.')
        : h('p', { class: 'muted' }, 'Load the resource pack of the server to see its glyphs in titles, move them into place, and see custom items.'),
      this.catalog.length > 0 && this.names())
  }

  loader() {
    const file = h('input', { type: 'file', accept: '.zip', onchange: () => file.files[0] && this.setPack(file.files[0], file.files[0].name) })
    const url = h('input', { type: 'text', placeholder: 'https://…/pack.zip (the host must allow other sites to read it)' })
    return h('section', null,
      h('h2', null, 'Resource pack'),
      h('p', { class: 'muted' }, 'The pack is read in this browser and never uploaded.'),
      h('div', { class: 'row-buttons' },
        this.app.server && h('button', { onclick: () => this.discover() }, 'Use the server pack'),
        file,
        h('span', { class: 'url' }, url, h('button', { onclick: async () => {
          try { await this.setPack(await (await fetch(url.value)).blob(), url.value) } catch (e) { this.message = `Could not download it: ${e.message}`; this.app.draw() }
        } }, 'Download'))),
      this.message && h('p', null, this.message))
  }

  /** The glyph names the server's plugins define. */
  names() {
    return h('section', null, h('h2', null, `Glyphs of the plugins (${this.catalog.length})`),
      h('table', { class: 'names' }, this.catalog.map((g) => h('tr', null,
        h('td', null, g.plugin), h('td', null, h('code', null, g.name)), h('td', { class: 'mc-char' }, g.char),
        h('td', null, h('button', { onclick: () => navigator.clipboard?.writeText(`<glyph:${g.name}>`) }, 'Copy tag'))))))
  }
}
