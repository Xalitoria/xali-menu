// The toolbar under the window for the glyphs of the resource pack and of the server's plugins. It shows when the server has custom items or
// glyphs, or a pack is loaded. A glyph is picked from the strip, shown larger in the card, and put in the title at an offset that the buttons
// move; the window above shows it at once, so a glyph is lined up with the slots by looking at the window.

import { h, fill } from './dom.js'
import { DEFAULT_FONT } from './fonts.js'
import { parseHead, realign, insert } from './glyphs.js'

/** The most thumbnails drawn at once; a search narrows a font with more. */
const LIMIT = 400
const NAMED = '@named'

export class GlyphBar {
  /** @param {import('./app.js').App} app */
  constructor(app) {
    this.app = app
    this.query = ''
    /** The scroll of the strip, kept over redraws. */
    this.scroll = 0
  }

  get panel() { return this.app.pack }

  /** Whether there is anything to show: a pack, glyphs the plugins name, or custom items that probably come with a pack. */
  available() {
    return Boolean(this.panel.pack) || this.panel.catalog.length > 0 || this.app.customItems
  }

  view() {
    if (!this.available()) return h('footer', { class: 'glyphbar', hidden: true })
    const root = h('footer', { class: 'glyphbar' })
    if (!this.panel.pack) {
      return fill(root, h('div', { class: 'glyph-note' },
        h('b', null, 'Glyphs'),
        h('span', { class: 'muted' }, this.app.server ? 'The server has custom items but no resource pack could be read.' : 'Custom items and glyphs need the resource pack of the server.'),
        h('button', { onclick: () => { this.app.tab = 'Pack'; this.app.draw() } }, 'Load the pack')))
    }
    this.build(root).catch((error) => fill(root, h('span', { class: 'bad' }, `The glyphs could not be shown: ${error.message}`)))
    return root
  }

  async build(root) {
    const { panel } = this
    const fonts = await panel.fontMap()
    await panel.glyphFonts()
    const spaces = (fonts.get(DEFAULT_FONT) ?? { spaces: new Map() }).spaces
    const widthOf = (character, key) => fonts.get(key ?? DEFAULT_FONT)?.glyphs.get(character)?.advance ?? null
    const title = this.app.store.value.title ?? ''
    const head = parseHead(title, spaces, widthOf)
    const keys = [...panel.pack.fontKeys(), ...(panel.catalog.length ? [NAMED] : [])]
    if (!keys.includes(panel.fontKey)) panel.fontKey = keys[0]

    const search = h('input', { type: 'search', class: 'glyph-search', placeholder: 'Search by name or code', value: this.query,
      oninput: () => { this.query = search.value; this.fillStrip(strip, fonts) } })
    const font = h('select', { class: 'glyph-font', title: 'Font', onchange: () => { panel.fontKey = font.value; this.scroll = 0; this.query = ''; this.app.draw() } },
      keys.map((k) => h('option', { value: k, selected: k === panel.fontKey }, k === NAMED ? 'Named by plugins' : k)))
    const strip = h('div', { class: 'glyph-strip', onscroll: () => { this.scroll = strip.scrollLeft } })
    this.fillStrip(strip, fonts)

    fill(root,
      this.card(head, fonts, spaces, widthOf, title),
      h('div', { class: 'glyph-pick' }, h('div', { class: 'glyph-tools' }, font, search), strip))
    strip.scrollLeft = this.scroll
  }

  /** The card: the glyph that is picked, or the one at the start of the title, big, with the buttons that move it. */
  card(head, fonts, spaces, widthOf, title) {
    const { panel, app } = this
    const shown = panel.glyph ? { glyph: panel.glyph, font: panel.glyphFont ?? panel.fontKey } : head ? { glyph: head.glyph, font: head.font ?? DEFAULT_FONT } : null
    const named = shown && panel.catalog.find((g) => g.char === shown.glyph)
    const apply = (value) => {
      const next = head
        ? realign(head, value, spaces)
        : (panel.glyph ? insert(panel.glyph, panel.glyphFont && panel.glyphFont !== DEFAULT_FONT ? panel.glyphFont : null, widthOf(panel.glyph, panel.glyphFont ?? null), value, title, spaces) : null)
      if (!next) return
      app.store.set(['title'], next.text)
      if (next.rest !== 0) app.setStatus(`The space characters of the default font cannot move it exactly: ${next.rest}px are left.`, true)
    }
    const offset = h('input', { type: 'number', class: 'offset', value: head ? head.offset : 0, step: 1, 'aria-label': 'Offset in pixels',
      onkeydown: (e) => { if (e.key === 'Enter') apply(Number(offset.value)) } })
    const nudge = (delta) => () => apply((head ? head.offset : Number(offset.value)) + delta)
    const can = Boolean(head || panel.glyph)
    const canvas = h('canvas', { class: 'glyph-big', width: 64, height: 64 })
    if (shown) {
      const found = fonts.get(shown.font ?? DEFAULT_FONT)?.glyphs.get(shown.glyph)
      if (found) draw(canvas, found)
    }
    return h('div', { class: 'glyph-card' },
      h('div', { class: 'glyph-preview' }, canvas,
        h('div', { class: 'glyph-info' },
          h('b', null, named ? named.name : shown ? `U+${code(shown.glyph)}` : 'No glyph'),
          h('small', { class: 'muted' }, shown ? [named && `U+${code(shown.glyph)} · `, head && !panel.glyph ? 'in the title' : panel.glyph && !head ? 'not in the title yet' : 'picked'] : 'Pick one from the strip'))),
      h('div', { class: 'glyph-move' },
        h('span', { class: 'muted' }, head ? `At ${head.offset} px` : 'Offset'),
        h('div', { class: 'stepper' },
          [-8, -1].map((d) => h('button', { onclick: nudge(d), disabled: !can, title: `${d} px` }, `${d}`)),
          offset,
          [1, 8].map((d) => h('button', { onclick: nudge(d), disabled: !can, title: `+${d} px` }, `+${d}`))),
        h('div', { class: 'row' },
          h('button', { class: 'primary', disabled: !can, onclick: () => apply(Number(offset.value)) }, head ? 'Move' : 'Put in title'),
          head && h('button', { class: 'danger', onclick: () => app.store.set(['title'], head.rest) }, 'Remove'))))
  }

  /** The thumbnails of the font, narrowed by the search. */
  async fillStrip(strip, fonts) {
    const { panel } = this
    const query = this.query.trim().toLowerCase()
    const found = (await panel.glyphFonts())
    const list = []
    if (panel.fontKey === NAMED) {
      for (const g of panel.catalog) {
        const key = g.font || found.get(g.char) || DEFAULT_FONT
        list.push({ character: g.char, name: g.name, plugin: g.plugin, key, glyph: fonts.get(key)?.glyphs.get(g.char) })
      }
    } else {
      const loaded = fonts.get(panel.fontKey)
      for (const [character, glyph] of loaded?.glyphs ?? []) list.push({ character, name: panel.catalog.find((g) => g.char === character)?.name ?? null, key: panel.fontKey, glyph })
    }
    const matching = list.filter((e) => !query || (e.name ?? '').toLowerCase().includes(query) || code(e.character).toLowerCase().includes(query.replace(/^u\+/, '')))
    fill(strip,
      matching.length === 0 && h('span', { class: 'muted pad' }, list.length ? 'No glyph matches.' : 'This font has no picture glyphs.'),
      matching.slice(0, LIMIT).map((e) => {
        let thumb = h('span', { class: 'mc-char' }, e.character)
        if (e.glyph) { thumb = h('canvas', { width: 40, height: 40 }); draw(thumb, e.glyph) }
        return h('button', { class: `glyph ${panel.glyph === e.character ? 'active' : ''}`, title: `${e.name ?? ''} U+${code(e.character)}${e.glyph ? `, ${e.glyph.advance} wide` : ''}`.trim(),
          onclick: () => { panel.glyph = e.character; panel.glyphFont = e.key; this.app.draw() } }, thumb, e.name && h('small', null, e.name))
      }),
      matching.length > LIMIT && h('span', { class: 'muted pad' }, `${matching.length - LIMIT} more: search to narrow the list.`))
    strip.scrollLeft = this.scroll
  }
}

const code = (character) => character.codePointAt(0).toString(16).toUpperCase().padStart(4, '0')

/** Draws [glyph] (a picture of a font) into [canvas], as big as fits, with its pixels kept sharp. */
function draw(canvas, glyph) {
  const ctx = canvas.getContext('2d')
  ctx.imageSmoothingEnabled = false
  const fit = Math.min(canvas.width / glyph.sw, canvas.height / glyph.sh)
  const w = glyph.sw * fit
  const h2 = glyph.sh * fit
  ctx.drawImage(glyph.image, glyph.sx, glyph.sy, glyph.sw, glyph.sh, (canvas.width - w) / 2, (canvas.height - h2) / 2, w, h2)
}
