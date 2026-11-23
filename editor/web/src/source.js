// Where menus come from and go back to: the server the link points at, a link that carries one menu, or files on the disk.

import { parse } from './hocon.js'
import { write } from './hoconWriter.js'

/** Pattern of a short 12-character URL-safe base64 session code (`https://menu.xali.to/#<code>`). */
export const SHORT_CODE = /^[A-Za-z0-9_-]{12}$/

/** Default relay URL paired with the hosted editor (`https://menu.xali.to/relay`). */
export function defaultRelay(base = globalThis.location?.href) {
  if (typeof base === 'string' && /^https?:\/\//i.test(base)) {
    return new URL('relay', base).href.replace(/\/+$/, '')
  }
  return 'https://menu.xali.to/relay'
}

/** What the address after the `#` asks for. @returns {{code?: string, relay?: string, session?: string, key?: string, server?: string, token?: string, menu?: string, data?: string, name?: string}} */
export function fromHash(hash) {
  const clean = String(hash ?? '').replace(/^#/, '').trim()
  if (!clean) return {}
  const parts = clean.split('&')
  if (!parts[0].includes('=') && SHORT_CODE.test(parts[0])) {
    const rest = parts.length > 1 ? Object.fromEntries([...new URLSearchParams(parts.slice(1).join('&')).entries()]) : {}
    return { code: parts[0], ...rest }
  }
  return Object.fromEntries([...new URLSearchParams(clean).entries()])
}

/** The link the plugin prints for a menu, read back: gzip and base64url of `{"menu": ...}`. */
export async function decodeData(data) {
  const bytes = Uint8Array.from(atob(data.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))
  return JSON.parse(await new Response(stream).text())
}

export async function encodeData(value) {
  const stream = new Blob([JSON.stringify(value)]).stream().pipeThrough(new CompressionStream('gzip'))
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer())
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Builds a shareable `#data=...&name=...` URL carrying [menu] under [name] (entirely in the fragment, never sent to a static host). */
export async function dataLink(baseUrl, name, menu) {
  const packed = await encodeData({ menu })
  return `${String(baseUrl).split('#')[0]}#data=${packed}&name=${encodeURIComponent(name || 'menu')}`
}

/** A server with the editor endpoint turned on. */
export class Server {
  constructor(url, token, from = null) {
    this.url = url.replace(/\/+$/, '')
    this.token = token
    this.from = from
    /** True after a save that was held for admin approval (shared direct link). */
    this.held = false
    /** True after a save that was also reloaded live on the server (`--auto-reload`). */
    this.reloaded = false
  }

  async request(path, options = {}) {
    let response
    const headers = {
      Authorization: `Bearer ${this.token}`,
      ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(this.from ? { 'X-Editor-From': this.from } : {}),
      ...options.headers,
    }
    try {
      response = await fetch(`${this.url}/v1${path}`, { ...options, headers })
    } catch (error) {
      throw new Error(`Cannot reach ${this.url}. The page is served over https, so the server needs https too unless it is on this computer (${error.message}).`)
    }
    if (response.status === 401) throw new Error('The link has expired. Run /xmenu edit again.')
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.error ?? `The server answered ${response.status}`)
    }
    return response
  }

  async info() { return (await this.request('/info')).json() }

  async menu(name) { return (await (await this.request(`/menus/${encodeURIComponent(name)}`)).json()).menu }

  /** Writes the menu (or holds it for admin approval on a shared link). @returns {Promise<string[]>} the problems the server found in it */
  async save(name, menu) {
    const body = await (await this.request(`/menus/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify({ menu }) })).json()
    this.held = body.held === true
    this.reloaded = body.reloaded === true
    return body.problems ?? []
  }

  async check(name, menu) {
    const body = await (await this.request(`/menus/${encodeURIComponent(name)}/check`, { method: 'POST', body: JSON.stringify({ menu }) })).json()
    return body.problems ?? []
  }

  /** The resource pack the server uses, as a Blob, or null if it has none. */
  async pack() {
    const response = await this.request('/pack', { headers: { Accept: 'application/zip' } }).catch(() => null)
    return response ? response.blob() : null
  }

  /**
   * The pack to read for the editor: a Blob, or a file read by parts when the server cannot send it at once. Null if there is none.
   * @param {number|null} size the size of the pack, from `info`
   * @param {string|null} [hash] SHA-256 hash of the pack, for persistent browser chunk caching
   */
  async packFile(size, hash = null) { return this.pack() }

  /** What the plugins that own custom items (`nexo:ruby`) say they are: material, item model and custom model data. */
  async resolve(materials) {
    return (await (await this.request('/items', { method: 'POST', body: JSON.stringify({ materials }) })).json()).items ?? {}
  }

  /** The colors potions show: for each `{type, effects}`, an rgb number or null. */
  async colors(potions) {
    return (await (await this.request('/colors', { method: 'POST', body: JSON.stringify({ potions }) })).json()).colors ?? []
  }

  /** A file of the game's own resources that the page cannot get itself, such as `font/unifont.zip`, through the server. @returns {Promise<Uint8Array|null>} */
  async gameFile(file) {
    const response = await this.request(`/game-file?name=${encodeURIComponent(file)}`, { headers: { Accept: 'application/zip' } }).catch(() => null)
    return response ? new Uint8Array(await response.arrayBuffer()) : null
  }

  /** A skin of Mojang's texture server, through the server of the plugin. */
  async skin(url) {
    return (await this.request(`/skin?url=${encodeURIComponent(url)}`, { headers: { Accept: 'image/png' } })).blob()
  }

  /** A player's skin by username, resolved by the server (online player, SkinsRestorer, or Mojang). */
  async playerSkin(name) {
    return (await this.request(`/skin?player=${encodeURIComponent(name)}`, { headers: { Accept: 'image/png' } })).blob()
  }

  /** Every item a menu can use: `{vanilla: [...], custom: {prefix: [...]}}`. */
  async catalog() { return (await this.request('/catalog')).json() }

  /** Live server registries (sounds, particles, enchantments, effects, entity types, ...). */
  async registries() { return (await (await this.request('/registries')).json()).registries ?? {} }

  /** What a catalog lists now for the player of the link: `{elements, total}`, sorted as a paged menu would. Throws with the server's reason when it cannot. */
  async elements(catalog, sort, descending) {
    return (await this.request('/elements', { method: 'POST', body: JSON.stringify({ catalog, sort, descending }) })).json()
  }

  async glyphs() { return (await (await this.request('/glyphs')).json()).glyphs ?? [] }
}

/** Reads the text of a menu file (`.conf` or `.json`) into a menu. */
export function readFile(name, text) {
  return name.endsWith('.json') ? JSON.parse(text) : parse(text)
}

/** The file name and the text to save [menu] as. */
export function toFile(name, menu) {
  return { name: `${name}.conf`, text: write(menu) }
}

/** Offers [text] as a download called [name]. */
export function download(name, text) {
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
  link.download = name
  link.click()
  URL.revokeObjectURL(link.href)
}
