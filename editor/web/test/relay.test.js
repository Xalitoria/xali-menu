import { test } from 'node:test'
import assert from 'node:assert/strict'
import { seal, open, importKey, deriveCode, RelayServer } from '../src/relay.js'
import { Server, dataLink, decodeData, defaultRelay, fromHash, SHORT_CODE } from '../src/source.js'

const CODE = 'Ab9-_xK2pQ7m'
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
  const { session, key } = await deriveCode(CODE)
  const real = globalThis.fetch
  let seen
  globalThis.fetch = async (url, init) => {
    seen = { url, payload: await open(key, session, 'q', new Uint8Array(init.body)) }
    const answer = { rid: seen.payload.rid, status: 200, type: 'application/json', body: Buffer.from('{"menus":["a"]}').toString('base64') }
    return new Response(await seal(key, session, 'a', answer))
  }
  try {
    const server = RelayServer.fromCode('https://relay.example/', CODE, 'Alex')
    assert.deepEqual(await server.info(), { menus: ['a'] })
    assert.equal(seen.url, `https://relay.example/s/${session}/call`)
    assert.equal(seen.payload.path, '/info')
    assert.equal(seen.payload.method, 'GET')
    assert.equal(seen.payload.from, 'Alex')
    assert.ok(Math.abs(seen.payload.t - Date.now()) < 5000)
  } finally { globalThis.fetch = real }
})

test('an answer for another request, with another key, or an error status is not taken as the answer', async () => {
  const { session, key } = await deriveCode(CODE)
  const real = globalThis.fetch
  const respond = (make) => { globalThis.fetch = async (url, init) => { const p = await open(key, session, 'q', new Uint8Array(init.body)); return make(p) } }
  try {
    const server = RelayServer.fromCode('https://relay.example', CODE, 'Alex')
    respond(async (p) => new Response(await seal(key, session, 'a', { rid: 'other', status: 200, body: '' })))
    await assert.rejects(server.info(), /not for this request/)
    const wrong = await importKey(Buffer.from(new Uint8Array(32)).toString('base64url'))
    respond(async (p) => new Response(await seal(wrong, session, 'a', { rid: p.rid, status: 200, body: '' })))
    await assert.rejects(server.info(), /key of the link is wrong/)
    respond(async (p) => new Response(await seal(key, session, 'a', { rid: p.rid, status: 422, body: Buffer.from('{"error":"bad menu"}').toString('base64') })))
    await assert.rejects(server.info(), /bad menu/)
    respond(async () => new Response('', { status: 504 }))
    await assert.rejects(server.info(), /did not answer/)
    respond(async () => new Response('', { status: 404 }))
    await assert.rejects(server.info(), /has ended/)
  } finally { globalThis.fetch = real }
})

test('a save says when the admin has to approve it', async () => {
  const { session, key } = await deriveCode(CODE)
  const real = globalThis.fetch
  globalThis.fetch = async (url, init) => {
    const p = await open(key, session, 'q', new Uint8Array(init.body))
    assert.equal(p.method, 'PUT')
    assert.equal(p.path, '/menus/shop')
    assert.deepEqual(JSON.parse(p.body), { menu: { title: 'x' } })
    return new Response(await seal(key, session, 'a', { rid: p.rid, status: 200, body: Buffer.from('{"problems":["p"],"held":true}').toString('base64') }))
  }
  try {
    const server = RelayServer.fromCode('https://relay.example', CODE, 'Alex')
    assert.deepEqual(await server.save('shop', { title: 'x' }), ['p'])
    assert.equal(server.held, true)
  } finally { globalThis.fetch = real }
})

test('a pack is read through the relay in parts, with a Range for each', async () => {
  const { session, key } = await deriveCode(CODE)
  const real = globalThis.fetch
  const pack = Uint8Array.from({ length: 3000 }, (_, i) => i % 200)
  const asked = []
  globalThis.fetch = async (url, init) => {
    const p = await open(key, session, 'q', new Uint8Array(init.body))
    asked.push([p.path, p.range])
    const [, a, b] = p.range.match(/bytes=(\d+)-(\d+)/)
    return new Response(await seal(key, session, 'a', { rid: p.rid, status: 206, type: 'application/zip', body: Buffer.from(pack.slice(Number(a), Number(b) + 1)).toString('base64') }))
  }
  try {
    const server = RelayServer.fromCode('https://relay.example', CODE, 'Alex')
    const file = await server.packFile(3000)
    assert.equal(file.size, 3000)
    assert.deepEqual(new Uint8Array(await file.slice(10, 20).arrayBuffer()), pack.slice(10, 20))
    assert.deepEqual(asked, [['/pack', 'bytes=0-3000'.replace('3000', '2999')]])
  } finally { globalThis.fetch = real }
})

test('direct Server sends X-Editor-From on shared sessions, tracks held saves, and encodes shareable data links', async () => {
  const real = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, init) => {
    calls.push({ url, method: init.method ?? 'GET', headers: init.headers })
    if (url.includes('/skin?player=')) return new Response(new Uint8Array([1, 2, 3]), { status: 200 })
    return new Response(JSON.stringify({ problems: [], held: true }), { status: 200 })
  }
  try {
    const server = new Server('https://srv.example/', 'tok', 'Alex')
    await server.save('shop', { title: 'Shop' })
    assert.equal(server.held, true)
    assert.equal(calls[0].headers['X-Editor-From'], 'Alex')
    assert.equal(calls[0].headers['Content-Type'], 'application/json')
    await server.playerSkin('audizian')
    assert.equal(calls[1].url, 'https://srv.example/v1/skin?player=audizian')
    assert.equal(calls[1].headers['Content-Type'], undefined)
  } finally { globalThis.fetch = real }

  const url = await dataLink('https://menu.xali.to/#old=1', 'shop', { title: 'Shop', size: 27 })
  assert.ok(url.startsWith('https://menu.xali.to/#data='))
  const parsed = fromHash(url.slice(url.indexOf('#')))
  assert.equal(parsed.name, 'shop')
  assert.deepEqual((await decodeData(parsed.data)).menu, { title: 'Shop', size: 27 })
})

test('short 12-char base64url codes parse cleanly, derive the expected session/key, and track reloaded state', async () => {
  const code = 'Ab9-_xK2pQ7m'
  assert.equal(SHORT_CODE.test(code), true)
  assert.deepEqual(fromHash(`#${code}`), { code })
  assert.deepEqual(fromHash(code), { code })
  assert.deepEqual(fromHash(`#${code}&relay=http%3A%2F%2F127.0.0.1%3A8787`), { code, relay: 'http://127.0.0.1:8787' })
  assert.equal(defaultRelay('https://menu.xali.to/#Ab9-_xK2pQ7m'), 'https://menu.xali.to/relay')

  const { session, key } = await deriveCode(code)
  assert.equal(session, 'MDOBu5MeKKxKUvcD8VhhAA')

  const real = globalThis.fetch
  let reloadedReply = false
  globalThis.fetch = async (url, init) => {
    assert.equal(url, `https://menu.xali.to/relay/s/${session}/call`)
    const req = await open(key, session, 'q', new Uint8Array(init.body))
    const body = req.method === 'PUT'
      ? JSON.stringify({ problems: [], reloaded: reloadedReply })
      : JSON.stringify({ menus: ['a', 'b'], shared: false, readOnly: false, autoReload: reloadedReply })
    return new Response(await seal(key, session, 'a', { rid: req.rid, status: 200, type: 'application/json', body: Buffer.from(body).toString('base64') }))
  }
  try {
    const server = RelayServer.fromCode('https://menu.xali.to/relay', code)
    const info = await server.info()
    assert.deepEqual(info.menus, ['a', 'b'])
    await server.save('a', { title: 'A' })
    assert.equal(server.held, false)
    assert.equal(server.reloaded, false)
    reloadedReply = true
    await server.save('b', { title: 'B' })
    assert.equal(server.reloaded, true)
  } finally { globalThis.fetch = real }
})
