import { test } from 'node:test'
import assert from 'node:assert/strict'
import { seal, open, importKey, RelayServer } from '../src/relay.js'

const KEY = Buffer.from(new Uint8Array(32).map((_, i) => i + 1)).toString('base64url')

test('what is sealed is read back with the same key, session and direction only', async () => {
  const key = await importKey(KEY)
  const bytes = await seal(key, 'abc', 'q', { hello: 'world' })
  assert.deepEqual(await open(key, 'abc', 'q', bytes), { hello: 'world' })
  await assert.rejects(open(key, 'abd', 'q', bytes))
  await assert.rejects(open(key, 'abc', 'a', bytes))
  const other = await importKey(Buffer.from(new Uint8Array(32)).toString('base64url'))
  await assert.rejects(open(other, 'abc', 'q', bytes))
  const damaged = bytes.slice(); damaged[20] ^= 1
  await assert.rejects(open(key, 'abc', 'q', damaged))
})

test('every message has its own iv', async () => {
  const key = await importKey(KEY)
  const [a, b] = [await seal(key, 's', 'q', 1), await seal(key, 's', 'q', 1)]
  assert.notDeepEqual(a.slice(0, 12), b.slice(0, 12))
})

test('a request goes through the relay and the answer is checked against it', async () => {
  const key = await importKey(KEY)
  const real = globalThis.fetch
  let seen
  globalThis.fetch = async (url, init) => {
    seen = { url, payload: await open(key, 's1', 'q', new Uint8Array(init.body)) }
    const answer = { rid: seen.payload.rid, status: 200, type: 'application/json', body: Buffer.from('{"menus":["a"]}').toString('base64') }
    return new Response(await seal(key, 's1', 'a', answer))
  }
  try {
    const server = new RelayServer('https://relay.example/', 's1', KEY, 'Alex')
    assert.deepEqual(await server.info(), { menus: ['a'] })
    assert.equal(seen.url, 'https://relay.example/s/s1/call')
    assert.equal(seen.payload.path, '/info')
    assert.equal(seen.payload.method, 'GET')
    assert.equal(seen.payload.from, 'Alex')
    assert.ok(Math.abs(seen.payload.t - Date.now()) < 5000)
  } finally { globalThis.fetch = real }
})

test('an answer for another request, with another key, or an error status is not taken as the answer', async () => {
  const key = await importKey(KEY)
  const real = globalThis.fetch
  const respond = (make) => { globalThis.fetch = async (url, init) => { const p = await open(key, 's1', 'q', new Uint8Array(init.body)); return make(p) } }
  try {
    const server = new RelayServer('https://relay.example', 's1', KEY, 'Alex')
    respond(async (p) => new Response(await seal(key, 's1', 'a', { rid: 'other', status: 200, body: '' })))
    await assert.rejects(server.info(), /not for this request/)
    const wrong = await importKey(Buffer.from(new Uint8Array(32)).toString('base64url'))
    respond(async (p) => new Response(await seal(wrong, 's1', 'a', { rid: p.rid, status: 200, body: '' })))
    await assert.rejects(server.info(), /key of the link is wrong/)
    respond(async (p) => new Response(await seal(key, 's1', 'a', { rid: p.rid, status: 422, body: Buffer.from('{"error":"bad menu"}').toString('base64') })))
    await assert.rejects(server.info(), /bad menu/)
    respond(async () => new Response('', { status: 504 }))
    await assert.rejects(server.info(), /did not answer/)
    respond(async () => new Response('', { status: 404 }))
    await assert.rejects(server.info(), /has ended/)
  } finally { globalThis.fetch = real }
})

test('a save says when the admin has to approve it', async () => {
  const key = await importKey(KEY)
  const real = globalThis.fetch
  globalThis.fetch = async (url, init) => {
    const p = await open(key, 's1', 'q', new Uint8Array(init.body))
    assert.equal(p.method, 'PUT')
    assert.equal(p.path, '/menus/shop')
    assert.deepEqual(JSON.parse(p.body), { menu: { title: 'x' } })
    return new Response(await seal(key, 's1', 'a', { rid: p.rid, status: 200, body: Buffer.from('{"problems":["p"],"held":true}').toString('base64') }))
  }
  try {
    const server = new RelayServer('https://relay.example', 's1', KEY, 'Alex')
    assert.deepEqual(await server.save('shop', { title: 'x' }), ['p'])
    assert.equal(server.held, true)
  } finally { globalThis.fetch = real }
})

test('a pack is read through the relay in parts, with a Range for each', async () => {
  const key = await importKey(KEY)
  const real = globalThis.fetch
  const pack = Uint8Array.from({ length: 3000 }, (_, i) => i % 200)
  const asked = []
  globalThis.fetch = async (url, init) => {
    const p = await open(key, 's1', 'q', new Uint8Array(init.body))
    asked.push([p.path, p.range])
    const [, a, b] = p.range.match(/bytes=(\d+)-(\d+)/)
    return new Response(await seal(key, 's1', 'a', { rid: p.rid, status: 206, type: 'application/zip', body: Buffer.from(pack.slice(Number(a), Number(b) + 1)).toString('base64') }))
  }
  try {
    const server = new RelayServer('https://relay.example', 's1', KEY, 'Alex')
    const file = await server.packFile(3000)
    assert.equal(file.size, 3000)
    assert.deepEqual(new Uint8Array(await file.slice(10, 20).arrayBuffer()), pack.slice(10, 20))
    assert.deepEqual(asked, [['/pack', 'bytes=0-3000'.replace('3000', '2999')]])
  } finally { globalThis.fetch = real }
})
