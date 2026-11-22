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
  assert.deepEqual(await health.json(), { ok: true })

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

test('worker supports caller-chosen derived session ids, long-poll, answer, and delete', async () => {
  const env = makeEnv()
  const id = 'MDOBu5MeKKxKUvcD8VhhAA'

  assert.equal((await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id: 'bad' }) })).status, 400)

  const created = await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id, ttl: 600 }) })
  assert.equal(created.status, 201)
  const { secret, ttl } = await created.json()
  assert.equal(ttl, 600)

  assert.equal((await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id, ttl: 600 }) })).status, 409)
  assert.equal((await call(env, `/relay/s/${id}/poll`, { headers: bearer('wrong') })).status, 401)

  // Start long-poll before the browser call arrives.
  const pollPromise = call(env, `/relay/s/${id}/poll?wait=5`, { headers: bearer(secret) })
  await new Promise((r) => setTimeout(r, 20))

  const callPromise = call(env, `/relay/s/${id}/call`, { method: 'POST', body: new TextEncoder().encode('cipher-req') })
  const polled = await (await pollPromise).json()
  assert.equal(polled.length, 1)
  assert.equal(Buffer.from(polled[0].data, 'base64url').toString(), 'cipher-req')

  const ans = await call(env, `/relay/s/${id}/answer/${polled[0].id}`, {
    method: 'POST',
    headers: bearer(secret),
    body: new TextEncoder().encode('cipher-res'),
  })
  assert.equal(ans.status, 204)

  const callRes = await callPromise
  assert.equal(callRes.status, 200)
  assert.equal(Buffer.from(await callRes.arrayBuffer()).toString(), 'cipher-res')

  // End session -> subsequent poll returns 404.
  assert.equal((await call(env, `/relay/s/${id}`, { method: 'DELETE', headers: bearer(secret) })).status, 204)
  assert.equal((await call(env, `/relay/s/${id}/poll`, { headers: bearer(secret) })).status, 404)
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

test('RelayServer.fromCode talks end-to-end encrypted through the Cloudflare Worker', async () => {
  const env = makeEnv({ XM_RELAY_ORIGINS: 'https://menu.xali.to' })
  const code = 'Ab9-_xK2pQ7m'
  const { session, key } = await deriveCode(code)

  const { secret } = await (await call(env, '/relay/s', { method: 'POST', body: JSON.stringify({ id: session, ttl: 300 }) })).json()

  // Simulated plugin side polling the Worker and answering encrypted requests.
  const pluginLoop = (async () => {
    const res = await call(env, `/relay/s/${session}/poll?wait=5`, { headers: bearer(secret) })
    const [job] = await res.json()
    const req = await open(key, session, 'q', Buffer.from(job.data, 'base64url'))
    assert.equal(req.method, 'GET')
    assert.equal(req.path, '/info')
    const reply = { rid: req.rid, status: 200, type: 'application/json', body: Buffer.from(JSON.stringify({ menus: ['shop', 'kits'], shared: false })).toString('base64') }
    await call(env, `/relay/s/${session}/answer/${job.id}`, {
      method: 'POST',
      headers: bearer(secret),
      body: await seal(key, session, 'a', reply),
    })
  })()

  const realFetch = globalThis.fetch
  globalThis.fetch = (url, init) => worker.fetch(new Request(url, { ...init, headers: { Origin: 'https://menu.xali.to', ...(init?.headers ?? {}) } }), env)
  try {
    const client = RelayServer.fromCode('https://menu.xali.to/relay', code)
    const info = await client.info()
    assert.deepEqual(info, { menus: ['shop', 'kits'], shared: false })
    await pluginLoop
  } finally {
    globalThis.fetch = realFetch
  }
})
