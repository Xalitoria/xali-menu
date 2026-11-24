// The editor's way to a server that cannot be reached from the page: through a relay. The page and the plugin share a key that is only in
// the link's fragment; each request is encrypted with it (AES-GCM) and posted to the relay, which holds it until the plugin fetches it,
// and the answer comes back the same way. The relay only ever sees encrypted bytes, and the plugin refuses what is not made with the key.
//
//   request  {rid, t, from, method, path, body}      (JSON, encrypted, bound to the session by the associated data "xm1:<session>:q")
//   answer   {rid, status, type, body}              (JSON with body in base64, "xm1:<session>:a")
//   message  12 bytes of iv, then the AES-GCM output

import { Server } from './source.js'
import { RangeFile, BlockCache } from './remote.js'
import { createRelayCrypto, bytesOf, base64 } from './relayCore.js'

const { deriveCode, seal, open, importKey } = createRelayCrypto({
  salt: 'xalimenu:session:v2',
  aadPrefix: 'xm1',
})

export { deriveCode, seal, open, importKey }

/** The editor's server when it is reached through a relay. */
export class RelayServer extends Server {
  /**
   * @param {string} relay the address of the relay
   * @param {string|null} session the session of the link (or null when [key] is a derived credentials promise)
   * @param {string|Promise<{session: string, key: CryptoKey}>} key the key of the link (base64url), or a derived credentials promise
   * @param {string} [from] the name the admin sees on shared edits
   */
  constructor(relay, session, key, from = '') {
    super(relay, '')
    this.session = session
    this.from = from
    this.credsPromise = typeof key === 'string'
      ? importKey(key).then((cryptoKey) => ({ session, key: cryptoKey }))
      : key
    /** True after a save the admin has yet to approve. */
    this.held = false
    /** True after a save that was also reloaded live on the server (`--auto-reload`). */
    this.reloaded = false
  }

  /** Connects to [relay] using a short 12-character base64url code from `/xmenu edit` or `/xmenu share`. */
  static fromCode(relay, code, from = '') {
    return new RelayServer(relay, null, deriveCode(code), from)
  }

  async request(path, options = {}) {
    const { session, key } = await this.credsPromise
    this.session = session
    const rid = base64(crypto.getRandomValues(new Uint8Array(12)))
    const payload = { rid, t: Date.now(), from: this.from, method: options.method ?? 'GET', path: path.startsWith('/') ? path : `/${path}`, body: options.body ?? null, range: options.headers?.Range ?? null }
    let response
    try {
      response = await fetch(`${this.url}/s/${session}/call`, { method: 'POST', body: await seal(key, session, 'q', payload) })
    } catch (error) {
      throw new Error(`Cannot reach the relay at ${this.url} (${error.message}).`)
    }
    if (response.status === 404) throw new Error('This link has ended. Run /xmenu edit again.')
    if (response.status === 504) throw new Error('The server did not answer. It may be offline, or the session may have ended.')
    if (!response.ok) throw new Error(`The relay answered ${response.status}`)
    let answer
    try {
      answer = await open(key, session, 'a', new Uint8Array(await response.arrayBuffer()))
    } catch {
      throw new Error('The answer could not be read: the key of the link is wrong.')
    }
    if (answer.rid !== rid) throw new Error('The answer is not for this request.')
    const body = bytesOf(answer.body ?? '')
    if (answer.status === 401) throw new Error('The link has ended.')
    if (answer.status >= 400) {
      const parsed = (() => { try { return JSON.parse(new TextDecoder().decode(body)) } catch { return {} } })()
      throw new Error(parsed.error ?? `The server answered ${answer.status}`)
    }
    return new Response(body, { status: answer.status, headers: { 'Content-Type': answer.type ?? 'application/json' } })
  }

  /** The pack is read in parts of 1 MB as the zip asks for them, cached in IndexedDB by [hash] so unchanged packs transfer zero bytes. */
  async packFile(size, hash = null) {
    if (!size) return super.packFile(size, hash)
    const cache = BlockCache.forHash(hash)
    return new RangeFile(size, async (start, end) => new Uint8Array(await (await this.request('/pack', { headers: { Range: `bytes=${start}-${end - 1}` } })).arrayBuffer()), 1024 * 1024, cache)
  }

  async save(name, menu) {
    const body = await (await this.request(`/menus/${encodeURIComponent(name)}`, { method: 'PUT', body: JSON.stringify({ menu }) })).json()
    this.held = body.held === true
    this.reloaded = body.reloaded === true
    return body.problems ?? []
  }
}
