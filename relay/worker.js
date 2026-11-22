// Cloudflare Worker + Durable Object version of the XaliMenu relay.
// Implements the exact same HTTP protocol as `relay.js` (including both `/s/...` and `/relay/s/...` paths, plus `/health`),
// with one in-memory Durable Object per session id and zero disk persistence.
//
// Deploy with `npx wrangler deploy` (routes `menu.xali.to/relay/*` to this Worker while `menu.xali.to/*` stays on GitHub Pages).

const encoder = new TextEncoder()
const decoder = new TextDecoder()

const DEFAULTS = {
  maxBody: 8 * 1024 * 1024,
  maxTtl: 4 * 3600,
  idle: 120,
  callTimeout: 30,
  pollWait: 25,
}

const SESSION_ID = /^[A-Za-z0-9_-]{16,43}$/

function url64(bytes) {
  let bin = ''
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function randomId(byteLength) {
  return url64(crypto.getRandomValues(new Uint8Array(byteLength)))
}

function equalStrings(a, b) {
  const left = encoder.encode(a)
  const right = encoder.encode(b)
  if (left.byteLength !== right.byteLength) return false
  if (typeof crypto.subtle?.timingSafeEqual === 'function') return crypto.subtle.timingSafeEqual(left, right)
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= left[i] ^ right[i]
  return diff === 0
}

function bearerOf(request) {
  const header = request.headers.get('authorization') ?? ''
  return header.startsWith('Bearer ') ? header.slice(7) : ''
}

function parseOrigins(raw) {
  if (!raw || raw.trim() === '' || raw.trim() === '*') return null
  return raw.split(',').map((s) => s.trim()).filter(Boolean)
}

function jsonResponse(status, body, extraHeaders = {}) {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: {
      ...(body === null ? {} : { 'Content-Type': 'application/json' }),
      'Cache-Control': 'no-store',
      ...extraHeaders,
    },
  })
}

async function readBytes(request, maxBody) {
  const cl = Number(request.headers.get('content-length'))
  if (Number.isFinite(cl) && cl > maxBody) return null
  if (!request.body) return new Uint8Array(0)
  const reader = request.body.getReader()
  const chunks = []
  let total = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > maxBody) {
      await reader.cancel().catch(() => {})
      return null
    }
    chunks.push(value)
  }
  const out = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    out.set(chunk, offset)
    offset += chunk.byteLength
  }
  return out
}

function withCors(response, corsHeaders) {
  const headers = new Headers(response.headers)
  headers.set('Cache-Control', 'no-store')
  for (const [k, v] of Object.entries(corsHeaders)) headers.set(k, v)
  return new Response(response.body, { status: response.status, headers })
}

export default {
  async fetch(request, env) {
    const origins = parseOrigins(env?.XM_RELAY_ORIGINS)
    const origin = request.headers.get('origin')
    const allowed = origin && (origins === null || origins.includes(origin))
    const cors = allowed
      ? { 'Access-Control-Allow-Origin': origins === null ? '*' : origin, Vary: 'Origin' }
      : {}

    if (origin && !allowed) return jsonResponse(403, { error: 'This site may not use the relay' })
    if (request.method === 'OPTIONS') {
      return jsonResponse(204, null, {
        ...cors,
        'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type',
        'Access-Control-Max-Age': '600',
      })
    }

    const url = new URL(request.url)
    const rawParts = url.pathname.split('/').filter(Boolean)
    const parts = rawParts[0] === 'relay' ? rawParts.slice(1) : rawParts

    if (parts.length === 1 && parts[0] === 'health' && request.method === 'GET') {
      return jsonResponse(200, { ok: true }, cors)
    }
    if (parts[0] !== 's') return jsonResponse(404, { error: 'Unknown request' }, cors)

    const maxBody = Number(env?.XM_RELAY_MAX_BODY) || DEFAULTS.maxBody

    if (parts.length === 1 && request.method === 'POST') {
      const token = env?.XM_RELAY_TOKEN ?? ''
      if (token && !equalStrings(bearerOf(request), token)) {
        return jsonResponse(401, { error: 'A token is needed to open a session' }, cors)
      }
      const raw = await readBytes(request, Math.min(maxBody, 16 * 1024))
      if (raw === null) return jsonResponse(413, { error: 'The message is too big' }, cors)
      let parsed = {}
      try { parsed = JSON.parse(decoder.decode(raw) || '{}') } catch { /* default */ }

      const maxTtl = Number(env?.XM_RELAY_MAX_TTL) || DEFAULTS.maxTtl
      const ttl = Math.min(Math.max(Number(parsed.ttl) || 3600, 60), maxTtl)
      let id
      if (parsed.id !== undefined && parsed.id !== null) {
        if (typeof parsed.id !== 'string' || !SESSION_ID.test(parsed.id)) {
          return jsonResponse(400, { error: 'Invalid session id' }, cors)
        }
        id = parsed.id
      } else {
        id = randomId(16)
      }

      const stub = env.SESSIONS.get(env.SESSIONS.idFromName(id))
      const created = await stub.fetch('https://relay.internal/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, ttl }),
      })
      return withCors(created, cors)
    }

    const id = parts[1]
    if (!id || !SESSION_ID.test(id)) {
      return jsonResponse(404, { error: 'There is no such session, or it has ended' }, cors)
    }
    const stub = env.SESSIONS.get(env.SESSIONS.idFromName(id))
    const subPath = '/' + parts.slice(2).join('/') + url.search
    const headers = new Headers()
    const auth = request.headers.get('authorization')
    if (auth) headers.set('Authorization', auth)
    const contentLength = request.headers.get('content-length')
    if (contentLength) headers.set('Content-Length', contentLength)
    const contentType = request.headers.get('content-type')
    if (contentType) headers.set('Content-Type', contentType)

    const hasBody = request.method !== 'GET' && request.method !== 'HEAD' && request.method !== 'DELETE' && request.body !== null
    const forwarded = await stub.fetch(new Request(`https://relay.internal${subPath}`, {
      method: request.method,
      headers,
      body: hasBody ? request.body : undefined,
      ...(hasBody ? { duplex: 'half' } : {}),
      signal: request.signal,
    }))
    return withCors(forwarded, cors)
  },
}

/** One active relay session held strictly in memory (nothing is ever written to Durable Object storage). */
export class RelaySession {
  constructor(state, env = {}) {
    this.state = state
    this.env = env
    /** @type {{id: string, secret: string, expires: number, seen: number, queue: object[], waiting: Map<string, object>, poller: object|null}|null} */
    this.session = null
  }

  config() {
    return {
      maxBody: Number(this.env?.XM_RELAY_MAX_BODY) || DEFAULTS.maxBody,
      idle: Number(this.env?.XM_RELAY_IDLE) || DEFAULTS.idle,
      callTimeout: Number(this.env?.XM_RELAY_CALL_TIMEOUT) || DEFAULTS.callTimeout,
      pollWait: Number(this.env?.XM_RELAY_POLL_WAIT) || DEFAULTS.pollWait,
    }
  }

  checkExpiry() {
    if (!this.session) return
    const now = Date.now()
    const { idle } = this.config()
    if (this.session.expires < now || this.session.seen + idle * 1000 < now) this.end()
  }

  end() {
    const s = this.session
    if (!s) return
    this.session = null
    for (const call of s.waiting.values()) {
      clearTimeout(call.timer)
      call.resolve(jsonResponse(404, { error: 'The session has ended' }))
    }
    s.waiting.clear()
    s.queue.length = 0
    if (s.poller) {
      clearTimeout(s.poller.timer)
      s.poller.resolve(jsonResponse(404, { error: 'The session has ended' }))
      s.poller = null
    }
  }

  deliver() {
    const s = this.session
    if (!s || !s.poller || s.queue.length === 0) return
    const { resolve, timer } = s.poller
    clearTimeout(timer)
    s.poller = null
    const batch = s.queue.splice(0, 16)
    resolve(jsonResponse(200, batch.map((c) => ({ id: c.id, data: url64(c.data) }))))
  }

  async fetch(request) {
    this.checkExpiry()
    const url = new URL(request.url)
    const parts = url.pathname.split('/').filter(Boolean)
    const { maxBody, callTimeout, pollWait } = this.config()

    if (parts.length === 1 && parts[0] === 'create' && request.method === 'POST') {
      if (this.session) return jsonResponse(409, { error: 'That session id is already in use' })
      const { id, ttl } = await request.json()
      const secret = randomId(24)
      const now = Date.now()
      this.session = { id, secret, expires: now + ttl * 1000, seen: now, queue: [], waiting: new Map(), poller: null }
      return jsonResponse(201, { id, secret, ttl })
    }

    const s = this.session
    if (!s) return jsonResponse(404, { error: 'There is no such session, or it has ended' })

    if (parts.length === 0 && request.method === 'DELETE') {
      if (!equalStrings(bearerOf(request), s.secret)) return jsonResponse(401, { error: 'Not the secret of this session' })
      this.end()
      return jsonResponse(204, null)
    }

    if (parts.length === 1 && parts[0] === 'poll' && request.method === 'GET') {
      if (!equalStrings(bearerOf(request), s.secret)) return jsonResponse(401, { error: 'Not the secret of this session' })
      s.seen = Date.now()
      if (s.poller) {
        clearTimeout(s.poller.timer)
        s.poller.resolve(jsonResponse(204, null))
        s.poller = null
      }
      const wait = Math.min(Math.max(Number(url.searchParams.get('wait')) || 0, 0), pollWait)
      if (s.queue.length > 0) {
        const batch = s.queue.splice(0, 16)
        return jsonResponse(200, batch.map((c) => ({ id: c.id, data: url64(c.data) })))
      }
      if (wait === 0) return jsonResponse(204, null)
      return new Promise((resolve) => {
        const poller = { resolve, timer: null }
        poller.timer = setTimeout(() => {
          if (s.poller === poller) {
            s.poller = null
            s.seen = Date.now()
            resolve(jsonResponse(204, null))
          }
        }, wait * 1000)
        s.poller = poller
        request.signal?.addEventListener('abort', () => {
          if (s.poller === poller) {
            clearTimeout(poller.timer)
            s.poller = null
            resolve(jsonResponse(204, null))
          }
        }, { once: true })
      })
    }

    if (parts.length === 1 && parts[0] === 'call' && request.method === 'POST') {
      if (s.queue.length + s.waiting.size >= 64) return jsonResponse(429, { error: 'Too many requests are waiting' })
      const data = await readBytes(request, maxBody)
      if (data === null) return jsonResponse(413, { error: 'The message is too big' })
      return new Promise((resolve) => {
        const call = { id: randomId(9), data, timer: null, resolve }
        const drop = () => {
          clearTimeout(call.timer)
          s.waiting.delete(call.id)
          const at = s.queue.indexOf(call)
          if (at >= 0) s.queue.splice(at, 1)
        }
        call.timer = setTimeout(() => {
          drop()
          resolve(jsonResponse(504, { error: 'The server did not answer' }))
        }, callTimeout * 1000)
        s.waiting.set(call.id, call)
        request.signal?.addEventListener('abort', () => {
          drop()
          resolve(jsonResponse(499, { error: 'The request was canceled' }))
        }, { once: true })
        s.queue.push(call)
        this.deliver()
      })
    }

    if (parts.length === 2 && parts[0] === 'answer' && request.method === 'POST') {
      if (!equalStrings(bearerOf(request), s.secret)) return jsonResponse(401, { error: 'Not the secret of this session' })
      const data = await readBytes(request, maxBody)
      if (data === null) return jsonResponse(413, { error: 'The message is too big' })
      const call = s.waiting.get(parts[1])
      if (call) {
        clearTimeout(call.timer)
        s.waiting.delete(call.id)
        call.resolve(new Response(data, { status: 200, headers: { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' } }))
      }
      return jsonResponse(204, null)
    }

    return jsonResponse(404, { error: 'Unknown request' })
  }
}
