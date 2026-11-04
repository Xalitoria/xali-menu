// Where menus come from and go back to: the server the link points at, a link that carries one menu, or files on the disk.

import { parse } from './hocon.js'
import { write } from './hoconWriter.js'

/** What the address after the `#` asks for. @returns {{server?: string, token?: string, menu?: string, data?: string, name?: string}} */
export function fromHash(hash) {
  const params = new URLSearchParams(hash.replace(/^#/, ''))
  return Object.fromEntries([...params.entries()])
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

/** A server with the editor endpoint turned on. */
export class Server {
  constructor(url, token) {
    this.url = url.replace(/\/+$/, '')
    this.token = token
  }

  async request(path, options = {}) {
    let response
    try {
      response = await fetch(`${this.url}/v1${path}`, { ...options, headers: { Authorization: `Bearer ${this.token}`, 'Content-Type': 'application/json', ...options.headers } })
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

  /** Writes the menu. @returns {Promise<string[]>} the problems the server found in it */
  async save(name, menu) {
    const body = await (await this.request(`/menus/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify({ menu }) })).json()
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

  /** What the plugins that own custom items (`nexo:ruby`) say they are: material, item model and custom model data. */
  async resolve(materials) {
    return (await (await this.request('/items', { method: 'POST', body: JSON.stringify({ materials }) })).json()).items ?? {}
  }

  /** The colors potions show: for each `{type, effects}`, an rgb number or null. */
  async colors(potions) {
    return (await (await this.request('/colors', { method: 'POST', body: JSON.stringify({ potions }) })).json()).colors ?? []
  }

  /** A skin of Mojang's texture server, through the server of the plugin. */
  async skin(url) {
    return (await this.request(`/skin?url=${encodeURIComponent(url)}`, { headers: { Accept: 'image/png' } })).blob()
  }

  /** Every item a menu can use: `{vanilla: [...], custom: {prefix: [...]}}`. */
  async catalog() { return (await this.request('/catalog')).json() }

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
