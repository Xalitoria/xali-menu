// The "Pack" tab and everything that needs the resource pack: titles drawn with the pack's glyphs, the window with the glyph
// behind the slots, and the aligner that moves a glyph by rewriting the space characters at the start of the title.

import { h, fill } from './dom.js'
import { Pack } from './pack.js'
import { DEFAULT_FONT, key as fontKey, spaces as spaceMap } from './fonts.js'
import { parseHead, realign, insert } from './glyphs.js'
import { runs } from './mctext.js'
import { slotCount } from './check.js'
import { settings } from './icons.js'

/** The scale the window is drawn at: one pixel of the game is this many pixels on the page. */
export const SCALE = 3
const WIDTH = 176
const TITLE_COLOR = '#404040'

export class PackPanel {
  /** @param {import('./app.js').App} app */
  constructor(app) {
    this.app = app
    /** @type {Pack|null} */
    this.pack = null
    /** Glyphs the server's plugins define: `{name, char, font, plugin}`. */
    this.catalog = []
    this.fontKey = DEFAULT_FONT
    this.glyph = null
    this.message = ''
  }

  // ---- loading

  async setPack(blob, label) {
    this.message = 'Reading the pack…'
    this.app.draw()
    try {
      this.pack = await Pack.load(blob, label)
      settings.pack = this.pack
      this.fontKey = this.pack.fontKeys().includes(DEFAULT_FONT) ? DEFAULT_FONT : (this.pack.fontKeys()[0] ?? DEFAULT_FONT)
      const summary = await this.pack.summary()
      this.message = `${label}: ${summary.namespaces.length} namespaces, ${summary.fonts} fonts, ${summary.bitmaps} picture providers.`
    } catch (error) {
      this.pack = null
      settings.pack = null
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
    await this.resolveItems()
  }

  /** Looks up the custom items of the open menu, so their pictures can be found in the pack. */
  async resolveItems() {
    const server = this.app.server
    if (!server) return
    const materials = [...new Set(Object.values(this.app.store.value.items ?? {}).map((i) => i.material).filter((m) => typeof m === 'string' && /^[a-z0-9_-]+:/i.test(m) && !m.startsWith('minecraft:')))]
    if (materials.length === 0) return
    try { for (const [material, data] of Object.entries(await server.resolve(materials))) settings.resolved.set(material, data) } catch { /* optional */ }
  }

  // ---- drawing text

  /** The fonts the runs of [text] need, loaded. */
  async fontsFor(runList) {
    const keys = new Set(runList.map((r) => fontKey(r.font ?? DEFAULT_FONT)))
    const fonts = new Map()
    for (const k of keys) fonts.set(k, await this.pack.font(k))
    return fonts
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
          return found
        })(),
      }
    }
    return this.fontIndex.map
  }

  /** Turns the names plugins define (`<glyph:name>`, `:name:`, `%img_name%`) and `<shift:N>` into characters. */
  expand(text) {
    let result = text
    for (const glyph of this.catalog) {
      const font = glyph.font || this.fontIndex?.found?.get(glyph.char)
      const wrapped = font && font !== DEFAULT_FONT ? `<font:${font}>${glyph.char}</font>` : glyph.char
      for (const form of [`<glyph:${glyph.name}>`, `:${glyph.name}:`, `%img_${glyph.name}%`]) result = result.split(form).join(wrapped)
    }
    return result
  }

  /**
   * Draws [text] at [x], [y] (top of the line) on [ctx]. Characters the pack's fonts have are drawn as pictures, the rest as text.
   * @returns {Promise<number>} where the text ends, in canvas pixels
   */
  async drawText(ctx, text, x, y) {
    const found = await this.glyphFonts()
    this.fontIndex.found = found
    const list = runs(this.expand(text))
    const fonts = await this.fontsFor(list)
    ctx.textBaseline = 'top'
    for (const run of list) {
      const font = fonts.get(fontKey(run.font ?? DEFAULT_FONT))
      ctx.font = `${run.bold ? 'bold ' : ''}${8 * SCALE}px monospace`
      ctx.fillStyle = run.color ?? TITLE_COLOR
      for (const character of run.text) {
        const advance = font.draw(ctx, character, x, y, SCALE, run.color ?? '#ffffff')
        if (advance !== null) { x += advance * SCALE; continue }
        ctx.fillText(character, x, y)
        x += Math.round(ctx.measureText(character).width / SCALE) * SCALE
      }
    }
    return x
  }

  /** The title as a canvas, or null without a pack. */
  title(text) {
    if (!this.pack) return null
    const canvas = h('canvas', { class: 'title-canvas', width: WIDTH * SCALE, height: 14 * SCALE })
    this.drawText(canvas.getContext('2d'), text, 8 * SCALE, 3 * SCALE).catch(() => {})
    return canvas
  }

  /** The window of the menu: background, title with its glyphs, and the outline of the slots. Null without a pack or for a window that is not a chest. */
  window(menu, outline = true, margin = 0) {
    if (!this.pack || String(menu.type ?? 'chest').toLowerCase() !== 'chest') return null
    const rows = Math.max(1, Math.ceil(slotCount(menu) / 9))
    const m = margin * SCALE
    const canvas = h('canvas', { class: 'window-canvas', width: WIDTH * SCALE + 2 * m, height: (24 + rows * 18) * SCALE + 2 * m })
    const ctx = canvas.getContext('2d')
    // The margin shows what a glyph draws outside the window, as it does in the game; the window itself is the gray part.
    ctx.fillStyle = margin ? '#26292f' : '#c6c6c6'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = '#c6c6c6'
    ctx.fillRect(m, m, WIDTH * SCALE, (24 + rows * 18) * SCALE)
    this.drawText(ctx, menu.title ?? '', m + 8 * SCALE, m + 6 * SCALE).then(() => {
      if (!outline) return
      ctx.strokeStyle = 'rgba(0,0,0,0.35)'
      ctx.lineWidth = 1
      for (let slot = 0; slot < rows * 9; slot++) {
        ctx.strokeRect(m + (8 + (slot % 9) * 18) * SCALE + 0.5, m + (18 + Math.floor(slot / 9) * 18) * SCALE + 0.5, 18 * SCALE - 1, 18 * SCALE - 1)
      }
    }).catch(() => {})
    return canvas
  }

  // ---- the tab

  view() {
    return h('div', { class: 'page pack-tab' },
      this.loader(),
      this.pack ? [this.aligner(), this.glyphBrowser()] : h('p', { class: 'muted' }, 'Load the resource pack of the server to see its glyphs in titles, move them into place, and see custom items.'),
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

  glyphBrowser() {
    const fonts = this.pack.fontKeys()
    const select = h('select', { onchange: () => { this.fontKey = select.value; this.glyph = null; this.app.draw() } },
      fonts.map((k) => h('option', { value: k, selected: k === this.fontKey }, k)))
    const grid = h('div', { class: 'glyph-grid' })
    this.pack.font(this.fontKey).then((font) => {
      for (const [character, glyph] of font.glyphs) {
        const canvas = h('canvas', { width: 48, height: 48 })
        const ctx = canvas.getContext('2d')
        ctx.imageSmoothingEnabled = false
        const fit = Math.min(48 / glyph.sw, 48 / glyph.sh)
        ctx.drawImage(glyph.image, glyph.sx, glyph.sy, glyph.sw, glyph.sh, 0, 0, glyph.sw * fit, glyph.sh * fit)
        const code = character.codePointAt(0).toString(16).toUpperCase().padStart(4, '0')
        grid.append(h('button', { class: `glyph ${this.glyph === character ? 'active' : ''}`, title: `U+${code}, ${glyph.advance} wide`,
          onclick: () => { this.glyph = character; this.app.draw() } }, canvas, h('small', null, code)))
      }
      if (font.glyphs.size === 0) grid.append(h('p', { class: 'muted' }, 'This font has no picture glyphs.'))
    })
    return h('section', null, h('h2', null, 'Glyphs'), select, grid)
  }

  /** Moves the glyph at the start of the title, or puts the picked one there. */
  aligner() {
    const menu = this.app.store.value
    const title = menu.title ?? ''
    const preview = h('div', { class: 'preview-window' })
    Promise.all(this.pack.fontKeys().map(async (k) => [k, await this.pack.font(k)])).then((loaded) => {
      const fonts = new Map(loaded)
      // The space characters are in the default font; the glyph may be in another one.
      const spaces = (fonts.get(DEFAULT_FONT) ?? { spaces: new Map() }).spaces
      const widthOf = (c, k) => fonts.get(k ?? DEFAULT_FONT)?.glyphs.get(c)?.advance ?? null
      const head = parseHead(title, spaces, widthOf)
      const offset = h('input', { type: 'number', value: head ? head.offset : 0, step: 1 })
      const apply = (value) => {
        const next = head
          ? realign(head, value, spaces)
          : (this.glyph ? insert(this.glyph, this.fontKey, widthOf(this.glyph, this.fontKey), value, title, spaces) : null)
        if (!next) return
        this.app.store.set(['title'], next.text)
        if (next.rest !== 0) this.app.setStatus(`The space characters of the default font cannot move it exactly: ${next.rest}px are left.`, true)
      }
      const nudge = (delta) => () => apply((head ? head.offset : Number(offset.value)) + delta)
      fill(preview,
        h('div', { class: 'row-buttons' },
          head ? h('span', null, `Glyph at ${head.offset}px`) : h('span', { class: 'muted' }, this.glyph ? 'The picked glyph is not in the title yet.' : 'Pick a glyph below, or put one at the start of the title.'),
          offset,
          h('button', { onclick: () => apply(Number(offset.value)) }, head ? 'Move' : 'Put in title'),
          [-8, -1, 1, 8].map((d) => h('button', { onclick: nudge(d), disabled: !head && !this.glyph }, d > 0 ? `+${d}` : `${d}`)),
          head && h('button', { class: 'danger', onclick: () => this.app.store.set(['title'], head.rest) }, 'Remove')),
        this.window(menu, true, 64) ?? h('p', { class: 'muted' }, 'Only chest menus are drawn here.'))
    })
    return h('section', null, h('h2', null, 'Align a glyph'),
      h('p', { class: 'muted' }, 'A glyph is moved by the space characters in front of it. The text after it stays where it was. Moving it up or down is set by "ascent" in the pack\'s font file.'),
      preview)
  }

  /** The glyph names the server's plugins define. */
  names() {
    return h('section', null, h('h2', null, `Glyphs of the plugins (${this.catalog.length})`),
      h('table', { class: 'names' }, this.catalog.map((g) => h('tr', null,
        h('td', null, g.plugin), h('td', null, h('code', null, g.name)), h('td', { class: 'mc-char' }, g.char),
        h('td', null, h('button', { onclick: () => navigator.clipboard?.writeText(`<glyph:${g.name}>`) }, 'Copy tag'))))))
  }
}
