import { test } from 'node:test'
import assert from 'node:assert/strict'
import worker, { RelaySession } from '../worker.js'
import { RelayServer, deriveCode, open, seal } from '../../editor/web/src/relay.js'

/** In-memory DurableObjectNamespace stand-in for testing `worker.js` directly in Node. */
function makeEnv(overrides = {}) {
  const objects = new Map()
  const SESSIONS = {
    idFromName(name) { return name },
    get(id) {
      if (!objects.has(id)) objects.set(id, new RelaySession({}, env))
      const instance = objects.get(id)
      return { fetch: (input, init) => instance.fetch(input instanceof Request ? input : new Request(input, init)) }
    },
  }
  const env = { SESSIONS, ...overrides }
  return env
}

const call = (env, path, init) => worker.fetch(new Request(`https://menu.xali.to${path}`, init), env)
const bearer = (secret) => ({ Authorization: `Bearer ${secret}` })

test('worker serves health, CORS preflight, and blocks disallowed origins', async () => {
  const env = makeEnv({ XM_RELAY_ORIGINS: 'https://menu.xali.to' })
  const health = await call(env, '/relay/health')
  assert.equal(health.status, 200)
  assert.deepEqual(await health.json(), { ok: true, ws: true })

  const preflight = await call(env, '/relay/s/MDOBu5MeKKxKUvcD8VhhAA/call', {
    method: 'OPTIONS',
    headers: { Origin: 'https://menu.xali.to' },
  })
  assert.equal(preflight.status, 204)
  assert.equal(preflight.headers.get('access-control-allow-origin'), 'https://menu.xali.to')

  const denied = await call(env, '/relay/s/MDOBu5MeKKxKUvcD8VhhAA/call', {
    method: 'POST',
    headers: { Origin: 'https://evil.example' },
    body: 'x',
  })
  assert.equal(denied.status, 403)
})

test('worker validates caller-chosen derived session ids, auth, and delete', async () => {
  const env = makeEnv()
  const id = 'MDOBu5MeKKxKUvcD8VhhAA'

  assert.equal((await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id: 'bad' }) })).status, 400)

  const created = await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id, ttl: 600 }) })
  assert.equal(created.status, 201)
  const { secret, ttl, ws } = await created.json()
  assert.equal(ttl, 600)
  assert.equal(ws, true)

  assert.equal((await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id, ttl: 600 }) })).status, 409)
  assert.equal((await call(env, `/relay/s/${id}/ws`, { headers: bearer('wrong') })).status, 401)
  assert.equal((await call(env, `/relay/s/${id}/ws`, { headers: bearer(secret) })).status, 426)

  // End session -> subsequent requests return 404.
  assert.equal((await call(env, `/relay/s/${id}`, { method: 'DELETE', headers: bearer('wrong') })).status, 401)
  assert.equal((await call(env, `/relay/s/${id}`, { method: 'DELETE', headers: bearer(secret) })).status, 204)
  assert.equal((await call(env, `/relay/s/${id}/ws`, { headers: bearer(secret) })).status, 404)
})

test('worker enforces token, body limit, timeout, and idle expiry', async () => {
  const env = makeEnv({ XM_RELAY_TOKEN: 'topsecret', XM_RELAY_MAX_BODY: '64', XM_RELAY_CALL_TIMEOUT: '0.08', XM_RELAY_IDLE: '0.05' })
  assert.equal((await call(env, '/relay/s', { method: 'POST', body: '{}' })).status, 401)

  const made = await (await call(env, '/relay/s', { method: 'POST', headers: bearer('topsecret'), body: '{}' })).json()
  assert.equal((await call(env, `/relay/s/${made.id}/call`, { method: 'POST', body: 'x'.repeat(200) })).status, 413)
  assert.equal((await call(env, `/relay/s/${made.id}/call`, { method: 'POST', body: 'hi' })).status, 504)

  await new Promise((r) => setTimeout(r, 70))
  assert.equal((await call(env, `/relay/s/${made.id}/call`, { method: 'POST', body: 'hi' })).status, 404)
})

test('RelayServer.fromCode talks end-to-end encrypted over Hibernatable WebSocket through the Cloudflare Worker', async () => {
  let resolveSent
  const firstSent = new Promise((r) => { resolveSent = r })
  class FakeSocket {
    constructor() { this.sent = []; this.closed = false; this.attachment = null }
    send(msg) { this.sent.push(msg); resolveSent?.(msg) }
    close() { this.closed = true }
    serializeAttachment(v) { this.attachment = structuredClone(v) }
    deserializeAttachment() { return this.attachment ? structuredClone(this.attachment) : null }
  }
  const origPair = globalThis.WebSocketPair
  const sockets = []
  let serverSocket
  const state = {
    acceptWebSocket(ws) { sockets.push(ws) },
    getWebSockets() { return sockets.filter((s) => !s.closed) },
  }
  let doInstance
  const env = {
    XM_RELAY_ORIGINS: 'https://menu.xali.to',
    SESSIONS: {
      idFromName(name) { return name },
      get() {
        if (!doInstance) doInstance = new RelaySession(state, env)
        return { fetch: (input, init) => doInstance.fetch(input instanceof Request ? input : new Request(input, init)) }
      },
    },
  }
  globalThis.WebSocketPair = function () {
    const client = new FakeSocket()
    serverSocket = new FakeSocket()
    return { 0: client, 1: serverSocket }
  }
  const realFetch = globalThis.fetch
  try {
    const code = 'Ab9-_xK2pQ7m'
    const { session, key } = await deriveCode(code)
    const { secret } = await (await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id: session, ttl: 300 }) })).json()
    try {
      await doInstance.fetch(new Request('https://relay.internal/ws', { headers: { ...bearer(secret), Upgrade: 'websocket' } }))
    } catch { /* Node Response forbids status 101 after acceptWebSocket ran */ }

    globalThis.fetch = (url, init) => worker.fetch(new Request(url, { ...init, headers: { Origin: 'https://menu.xali.to', ...(init?.headers ?? {}) } }), env)
    const client = RelayServer.fromCode('https://menu.xali.to/relay', code)
    const infoPromise = client.info()
    const rawJob = await firstSent
    const job = JSON.parse(rawJob)
    const req = await open(key, session, 'q', Buffer.from(job.data, 'base64url'))
    assert.equal(req.method, 'GET')
    assert.equal(req.path, '/info')
    const reply = { rid: req.rid, status: 200, type: 'application/json', body: Buffer.from(JSON.stringify({ menus: ['shop', 'kits'], shared: false })).toString('base64') }
    const sealed = await seal(key, session, 'a', reply)
    await doInstance.webSocketMessage(serverSocket, JSON.stringify({ id: job.id, data: Buffer.from(sealed).toString('base64url') }))

    const info = await infoPromise
    assert.deepEqual(info, { menus: ['shop', 'kits'], shared: false })
  } finally {
    globalThis.fetch = realFetch
    globalThis.WebSocketPair = origPair
  }
})

test('RelaySession supports Hibernatable WebSockets across isolate hibernation, inline answers, and large HTTP fetch/answer', async () => {
  // Minimal Cloudflare WebSocketPair + DurableObjectState hibernation mock.
  let resolveSent
  const firstSent = new Promise((r) => { resolveSent = r })
  class FakeSocket {
    constructor() { this.sent = []; this.closed = false; this.attachment = null }
    send(msg) { this.sent.push(msg); resolveSent?.(msg) }
    close() { this.closed = true }
    serializeAttachment(v) { this.attachment = structuredClone(v) }
    deserializeAttachment() { return this.attachment ? structuredClone(this.attachment) : null }
  }
  const origPair = globalThis.WebSocketPair
  const socketsByTag = new Map()
  const state = {
    acceptWebSocket(ws, tags = []) {
      for (const tag of tags) {
        if (!socketsByTag.has(tag)) socketsByTag.set(tag, [])
        socketsByTag.get(tag).push(ws)
      }
    },
    getWebSockets(tag) { return (socketsByTag.get(tag) ?? []).filter((s) => !s.closed) },
  }
  const env = {}
  let lastServerSocket
  globalThis.WebSocketPair = function () {
    const client = new FakeSocket()
    const server = new FakeSocket()
    lastServerSocket = server
    return { 0: client, 1: server }
  }
  try {
    let doInstance = new RelaySession(state, env)
    const id = 'MDOBu5MeKKxKUvcD8VhhAA'
    const created = await doInstance.fetch(new Request('https://relay.internal/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, ttl: 600 }),
    }))
    assert.equal(created.status, 201)
    const { secret, ws } = await created.json()
    assert.equal(ws, true)

    // Plugin connects via WebSocket upgrade (simulated with raw DO fetch because Node Response forbids status 101).
    let upgraded = false
    try {
      await doInstance.fetch(new Request('https://relay.internal/ws', {
        method: 'GET',
        headers: { Authorization: `Bearer ${secret}`, Upgrade: 'websocket' },
      }))
      upgraded = true
    } catch (e) {
      // Node's undici `new Response(null, { status: 101 })` throws RangeError, after acceptWebSocket + serializeAttachment already ran.
      assert.match(String(e.message), /status/i)
      upgraded = true
    }
    assert.equal(upgraded, true)
    assert.ok(lastServerSocket.deserializeAttachment()?.secret === secret)

    // Simulate Cloudflare hibernating and recreating a brand-new RelaySession isolate (in-memory `this.session` is null!).
    doInstance = new RelaySession(state, env)
    assert.equal(doInstance.session, null)

    // 1. Small browser call wakes DO from hibernation and pushes inline over the plugin WebSocket.
    const smallCall = doInstance.fetch(new Request('https://relay.internal/call', {
      method: 'POST',
      body: new TextEncoder().encode('hello-ws'),
    }))
    await new Promise((r) => setTimeout(r, 10))
    assert.equal(lastServerSocket.sent.length, 1)
    const pushed = JSON.parse(lastServerSocket.sent.shift())
    assert.equal(Buffer.from(pushed.data, 'base64url').toString(), 'hello-ws')

    // Plugin replies inline over the WebSocket (`webSocketMessage`).
    await doInstance.webSocketMessage(lastServerSocket, JSON.stringify({
      id: pushed.id,
      data: Buffer.from('reply-ws').toString('base64url'),
    }))
    const smallRes = await smallCall
    assert.equal(smallRes.status, 200)
    assert.equal(Buffer.from(await smallRes.arrayBuffer()).toString(), 'reply-ws')

    // 2. Large (>600 KB) browser call notifies plugin with `{ id, fetch: true }` and plugin pulls via GET /call/<id> + replies via POST /answer/<id>.
    const bigBody = new Uint8Array(650 * 1024).fill(42)
    const bigCall = doInstance.fetch(new Request('https://relay.internal/call', { method: 'POST', body: bigBody }))
    await new Promise((r) => setTimeout(r, 10))
    const notice = JSON.parse(lastServerSocket.sent.shift())
    assert.equal(notice.fetch, true)

    const pulled = await doInstance.fetch(new Request(`https://relay.internal/call/${notice.id}`, {
      headers: { Authorization: `Bearer ${secret}` },
    }))
    assert.equal(pulled.status, 200)
    assert.equal((await pulled.arrayBuffer()).byteLength, bigBody.byteLength)

    const bigReply = new Uint8Array(700 * 1024).fill(99)
    const ans = await doInstance.fetch(new Request(`https://relay.internal/answer/${notice.id}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${secret}` },
      body: bigReply,
    }))
    assert.equal(ans.status, 204)
    const bigRes = await bigCall
    assert.equal(bigRes.status, 200)
    assert.equal((await bigRes.arrayBuffer()).byteLength, bigReply.byteLength)
  } finally {
    globalThis.WebSocketPair = origPair
  }
})

test('worker forwards CF-Connecting-IP (ignoring spoofed headers) and detects Paper server disconnect cleanly', async () => {
  class FakeSocket {
    constructor() { this.sent = []; this.closed = false; this.attachment = null }
    send(msg) { this.sent.push(msg) }
    close() { this.closed = true }
    serializeAttachment(v) { this.attachment = structuredClone(v) }
    deserializeAttachment() { return this.attachment ? structuredClone(this.attachment) : null }
  }
  const origPair = globalThis.WebSocketPair
  const sockets = []
  let serverSocket
  const state = {
    acceptWebSocket(ws) { sockets.push(ws) },
    getWebSockets() { return sockets.filter((s) => !s.closed) },
  }
  let doInstance
  const env = {
    XM_RELAY_RECONNECT_GRACE: '0.03',
    SESSIONS: {
      idFromName(name) { return name },
      get() {
        if (!doInstance) doInstance = new RelaySession(state, env)
        return { fetch: (input, init) => doInstance.fetch(input instanceof Request ? input : new Request(input, init)) }
      },
    },
  }
  globalThis.WebSocketPair = function () {
    const client = new FakeSocket()
    serverSocket = new FakeSocket()
    return { 0: client, 1: serverSocket }
  }
  try {
    const id = 'MDOBu5MeKKxKUvcD8VhhAA'
    const { secret } = await (await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id, ttl: 300 }) })).json()
    try {
      await doInstance.fetch(new Request('https://relay.internal/ws', { headers: { ...bearer(secret), Upgrade: 'websocket' } }))
    } catch { /* Node status 101 */ }

    // 1. CF-Connecting-IP is forwarded; spoofed X-Client-Ip is ignored.
    const smallCall = call(env, `/relay/s/${id}/call`, {
      method: 'POST',
      headers: { 'CF-Connecting-IP': '203.0.113.42', 'X-Client-Ip': '127.0.0.1' },
      body: new TextEncoder().encode('ip-test'),
    })
    await new Promise((r) => setTimeout(r, 10))
    const pushed = JSON.parse(serverSocket.sent.shift())
    assert.equal(pushed.ip, '203.0.113.42')
    await doInstance.webSocketMessage(serverSocket, JSON.stringify({ id: pushed.id, data: Buffer.from('ok').toString('base64url') }))
    assert.equal((await smallCall).status, 200)

    // 2. Large (>600 KB) call passes IP in both notice and X-Relay-Client-Ip header on GET /call/<id>.
    const bigCall = call(env, `/relay/s/${id}/call`, {
      method: 'POST',
      headers: { 'CF-Connecting-IP': '198.51.100.9' },
      body: new Uint8Array(650 * 1024).fill(7),
    })
    await new Promise((r) => setTimeout(r, 10))
    const bigNotice = JSON.parse(serverSocket.sent.shift())
    assert.equal(bigNotice.fetch, true)
    assert.equal(bigNotice.ip, '198.51.100.9')
    const pulled = await doInstance.fetch(new Request(`https://relay.internal/call/${bigNotice.id}`, { headers: bearer(secret) }))
    assert.equal(pulled.headers.get('x-relay-client-ip'), '198.51.100.9')
    await doInstance.fetch(new Request(`https://relay.internal/answer/${bigNotice.id}`, { method: 'POST', headers: bearer(secret), body: 'done' }))
    assert.equal((await bigCall).status, 200)

    // 3. Abrupt Paper server crash (abnormal close 1006): after reconnect grace expires, browser immediately gets 503 offline.
    serverSocket.closed = true
    await doInstance.webSocketClose(serverSocket, 1006)
    await new Promise((r) => setTimeout(r, 50))
    const offline = await call(env, `/relay/s/${id}/call`, { method: 'POST', body: 'after-crash' })
    assert.equal(offline.status, 503)
    const offlineBody = await offline.json()
    assert.equal(offlineBody.offline, true)

    // 4. Clean Paper shutdown (close 1000) ends the session immediately (404).
    const id2 = 'NDOBu5MeKKxKUvcD8VhhBB'
    doInstance = null
    sockets.length = 0
    const s2 = await (await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id: id2, ttl: 300 }) })).json()
    try {
      await doInstance.fetch(new Request('https://relay.internal/ws', { headers: { ...bearer(s2.secret), Upgrade: 'websocket' } }))
    } catch { /* Node status 101 */ }
    serverSocket.closed = true
    await doInstance.webSocketClose(serverSocket, 1000)
    assert.equal((await call(env, `/relay/s/${id2}/call`, { method: 'POST', body: 'gone' })).status, 404)
  } finally {
    globalThis.WebSocketPair = origPair
  }
})
