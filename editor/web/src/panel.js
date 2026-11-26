// The resource pack: loading it (from the server, a file or an address), what its fonts have, and the "Pack" tab. Titles are drawn with the
// pack's glyphs through `services`; the glyph bar of the editor screen picks and moves them.

import { h, fill } from './dom.js'
import { Pack } from './pack.js'
import { DEFAULT_FONT } from './fonts.js'
import { expandPlaceholders } from './placeholders.js'
import { PACK_FORMATS } from './versions.js'
import { settings as assetSettings } from './assets.js'

const PRIVATE_HOST = /^(localhost|127\.\d+\.\d+\.\d+|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|169\.254\.\d+\.\d+|0\.0\.0\.0|\[?::1\]?|\[?fc[0-9a-f]{2}:.*|\[?fd[0-9a-f]{2}:.*|\[?fe80:.*)$/i

/** Whether this page may fetch [url]: an https page cannot read plain http, except from this computer. */
export function readable(url) {
  try {
    const target = new URL(url)
    return target.protocol === 'https:' || (target.protocol === 'http:' && globalThis.location?.protocol !== 'https:')
  } catch { return false }
}

/** Validates that [url] is an allowed http(s) pack URL without credentials or private-network targets. */
export function validatePackUrl(url) {
  const target = new URL(String(url ?? '').trim())
  if (target.protocol !== 'https:' && (target.protocol !== 'http:' || globalThis.location?.protocol === 'https:')) {
    throw new Error('Only https:// pack URLs are allowed')
  }
  if (target.username || target.password) throw new Error('Pack URL must not contain credentials')
  const localPage = globalThis.location?.hostname === 'localhost' || globalThis.location?.hostname === '127.0.0.1'
  if (!localPage && PRIVATE_HOST.test(target.hostname)) throw new Error('Private network pack URLs are not allowed')
  return target
}

/** The file at [url] as a Blob; the host has to allow other sites to read it (CORS). */
export async function download(url) {
  const target = validatePackUrl(url)
  const response = await fetch(target.href, { credentials: 'omit' })
  if (!response.ok) throw new Error(`it answered ${response.status}`)
  return response.blob()
}
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
      const targetFormat = services.assets?.packFormat ?? null
      this.pack = await Pack.load(blob, label, targetFormat)
      configure(this.pack, services.version)
      services.text.preprocess = (text) => this.expand(text)
      await this.glyphFonts()
      this.fontKey = this.pack.fontKeys().includes(DEFAULT_FONT) ? DEFAULT_FONT : (this.pack.fontKeys()[0] ?? DEFAULT_FONT)
      const summary = await this.pack.summary()
      const fmtNote = summary.packFormat ? `, format ${summary.packFormat}` : ''
      const ovNote = summary.overlays ? `, ${summary.overlays} overlays` : ''
      this.message = `${label}: ${summary.namespaces.length} namespaces, ${summary.fonts} fonts, ${summary.bitmaps} picture providers${fmtNote}${ovNote}.`
    } catch (error) {
      this.pack = null
      configure(null, services.version)
      services.text.preprocess = (text) => this.expand(text)
      this.message = `The pack could not be read: ${error.message}`
    }
    this.app.draw()
  }

  /**
   * Asks the server for its glyphs and its pack. A pack that is hosted for the players (Nexo's S3 or LobFile upload, `editor.pack-url`) is
   * downloaded from there when this page may; otherwise it comes from the server, in parts when the way to it is a relay.
   */
  async discover() {
    const server = this.app.server
    if (!server) return
    const info = this.app.serverInfo ?? {}
    try { this.catalog = await server.glyphs() } catch { this.catalog = [] }
    if (this.catalog.length > 0) this.app.draw()
    if (info.packUrl && readable(info.packUrl)) {
      try {
        const host = new URL(info.packUrl).host
        this.message = `Downloading the pack from ${host}…`
        this.app.draw()
        const blob = await download(info.packUrl)
        if (!info.packSize || blob.size === info.packSize) return await this.setPack(blob, `the pack at ${host}`)
      } catch (error) {
        this.hostNote = `The pack could not be downloaded from its host (${error.message}); it comes from the server instead.`
      }
    }
    try {
      const file = await server.packFile(info.packSize ?? null, info.packHash ?? null)
      if (file) await this.setPack(file, 'the server pack')
      else if (this.hostNote) { this.message = this.hostNote; this.app.draw() }
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

  /** Turns Nexo, Oraxen and ItemsAdder glyph & shift placeholders into characters/shifts. */
  expand(text) {
    return expandPlaceholders(text, this.catalog, this.fontIndex?.found ?? null)
  }

  // ---- the tab

  view() {
    const versionPicker = h('section', null,
      h('h2', null, 'Minecraft version'),
      h('p', { class: 'muted' }, 'Determines which vanilla textures, item models (1.21.4+ item definitions vs legacy overrides), and pack overlays are rendered.'),
      h('div', { class: 'row-buttons' },
        h('select', {
          onchange: (e) => {
            configure(this.pack, e.target.value)
            services.text.preprocess = (text) => this.expand(text)
            this.app.draw()
          },
        }, PACK_FORMATS.map((entry) => h('option', { value: entry.version, selected: assetSettings.version === entry.branch }, `${entry.version} (pack_format ${entry.packFormat})`)))))
    return h('div', { class: 'page pack-tab' },
      this.loader(),
      versionPicker,
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
          try { await this.setPack(await download(url.value), url.value) } catch (e) { this.message = `Could not download it: ${e.message}`; this.app.draw() }
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
