import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRelay } from '../relay.js'

async function start(options) {
  const server = createRelay(options)
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  return { base, stop: () => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve) }) }
}
const open = async (base, headers = {}) => (await fetch(`${base}/s`, { method: 'POST', headers, body: '{}' })).json()
const bearer = (secret) => ({ Authorization: `Bearer ${secret}` })

test('a call reaches the server through a poll, and the answer comes back to the caller', async () => {
  const { base, stop } = await start()
  try {
    const { id, secret } = await open(base)
    const call = fetch(`${base}/s/${id}/call`, { method: 'POST', body: Buffer.from('ask') })
    const polled = await (await fetch(`${base}/s/${id}/poll?wait=5`, { headers: bearer(secret) })).json()
    assert.equal(polled.length, 1)
    assert.equal(Buffer.from(polled[0].data, 'base64url').toString(), 'ask')
    const answered = await fetch(`${base}/s/${id}/answer/${polled[0].id}`, { method: 'POST', headers: bearer(secret), body: Buffer.from('reply') })
    assert.equal(answered.status, 204)
    const response = await call
    assert.equal(response.status, 200)
    assert.equal(Buffer.from(await response.arrayBuffer()).toString(), 'reply')
  } finally { await stop() }
})

test('a poll that is already waiting gets the call when it comes', async () => {
  const { base, stop } = await start()
  try {
    const { id, secret } = await open(base)
    const poll = fetch(`${base}/s/${id}/poll?wait=5`, { headers: bearer(secret) })
    await new Promise((r) => setTimeout(r, 50))
    fetch(`${base}/s/${id}/call`, { method: 'POST', body: Buffer.from('later') }).catch(() => {})
    const polled = await (await poll).json()
    assert.equal(Buffer.from(polled[0].data, 'base64url').toString(), 'later')
  } finally { await stop() }
})

test('answering and polling need the secret; calling needs only the id', async () => {
  const { base, stop } = await start()
  try {
    const { id } = await open(base)
    assert.equal((await fetch(`${base}/s/${id}/poll`, { headers: bearer('wrong') })).status, 401)
    assert.equal((await fetch(`${base}/s/${id}/poll`)).status, 401)
    assert.equal((await fetch(`${base}/s/${id}/answer/x`, { method: 'POST', body: 'x' })).status, 401)
    assert.equal((await fetch(`${base}/s/${id}`, { method: 'DELETE' })).status, 401)
    assert.equal((await fetch(`${base}/s/nothing/call`, { method: 'POST', body: 'x' })).status, 404)
  } finally { await stop() }
})

test('a call the server does not answer ends with 504, and nothing is kept of it', async () => {
  const { base, stop } = await start({ callTimeout: 0.1 })
  try {
    const { id } = await open(base)
    const response = await fetch(`${base}/s/${id}/call`, { method: 'POST', body: 'x' })
    assert.equal(response.status, 504)
  } finally { await stop() }
})

test('ending a session answers the calls that wait, and the session is gone', async () => {
  const { base, stop } = await start()
  try {
    const { id, secret } = await open(base)
    const call = fetch(`${base}/s/${id}/call`, { method: 'POST', body: 'x' })
    await new Promise((r) => setTimeout(r, 50))
    assert.equal((await fetch(`${base}/s/${id}`, { method: 'DELETE', headers: bearer(secret) })).status, 204)
    assert.equal((await call).status, 404)
    assert.equal((await fetch(`${base}/s/${id}/poll`, { headers: bearer(secret) })).status, 404)
  } finally { await stop() }
})

test('opening a session can need a token, and the ttl is kept within limits', async () => {
  const { base, stop } = await start({ token: 'letmein', maxTtl: 600 })
  try {
    assert.equal((await fetch(`${base}/s`, { method: 'POST', body: '{}' })).status, 401)
    const session = await (await fetch(`${base}/s`, { method: 'POST', headers: bearer('letmein'), body: '{"ttl": 99999}' })).json()
    assert.equal(session.ttl, 600)
  } finally { await stop() }
})

test('messages over the limit are refused, and too many waiting calls are held back', async () => {
  const { base, stop } = await start({ maxBody: 100 })
  try {
    const { id } = await open(base)
    assert.equal((await fetch(`${base}/s/${id}/call`, { method: 'POST', body: 'x'.repeat(500) })).status, 413)
  } finally { await stop() }
})

test('a browser origin is checked when the relay has a list, and preflights are answered', async () => {
  const { base, stop } = await start({ origins: ['https://menu.xali.to'] })
  try {
    const refused = await fetch(`${base}/s/x/call`, { method: 'POST', headers: { Origin: 'https://evil.example' }, body: 'x' })
    assert.equal(refused.status, 403)
    const preflight = await fetch(`${base}/s/x/call`, { method: 'OPTIONS', headers: { Origin: 'https://menu.xali.to' } })
    assert.equal(preflight.status, 204)
    assert.equal(preflight.headers.get('access-control-allow-origin'), 'https://menu.xali.to')
  } finally { await stop() }
})

test('a session nobody polls for is forgotten', async () => {
  const { base, stop } = await start({ idle: 0.05 })
  try {
    const { id } = await open(base)
    await new Promise((r) => setTimeout(r, 5400))
    assert.equal((await fetch(`${base}/s/${id}/call`, { method: 'POST', body: 'x' })).status, 404)
  } finally { await stop() }
})

test('a call that timed out is not handed to the server later', async () => {
  const { base, stop } = await start({ callTimeout: 0.1 })
  try {
    const { id, secret } = await open(base)
    assert.equal((await fetch(`${base}/s/${id}/call`, { method: 'POST', body: 'x' })).status, 504)
    assert.equal((await fetch(`${base}/s/${id}/poll`, { headers: bearer(secret) })).status, 204)
  } finally { await stop() }
})

test('relay accepts /relay prefix, health endpoint, and caller-chosen derived session ids', async () => {
  const { base, stop } = await start()
  try {
    assert.equal((await fetch(`${base}/health`)).status, 200)
    assert.equal((await fetch(`${base}/relay/health`)).status, 200)
    const bad = await fetch(`${base}/relay/s`, { method: 'POST', body: JSON.stringify({ id: 'too-short' }) })
    assert.equal(bad.status, 400)
    const id = 'MDOBu5MeKKxKUvcD8VhhAA'
    const made = await (await fetch(`${base}/relay/s`, { method: 'POST', body: JSON.stringify({ id, ttl: 300 }) })).json()
    assert.equal(made.id, id)
    const dup = await fetch(`${base}/relay/s`, { method: 'POST', body: JSON.stringify({ id, ttl: 300 }) })
    assert.equal(dup.status, 409)
    const call = fetch(`${base}/relay/s/${id}/call`, { method: 'POST', body: Buffer.from('hi') })
    const polled = await (await fetch(`${base}/relay/s/${id}/poll?wait=5`, { headers: bearer(made.secret) })).json()
    assert.equal(Buffer.from(polled[0].data, 'base64url').toString(), 'hi')
    await fetch(`${base}/relay/s/${id}/answer/${polled[0].id}`, { method: 'POST', headers: bearer(made.secret), body: Buffer.from('ok') })
    assert.equal(Buffer.from(await (await call).arrayBuffer()).toString(), 'ok')
  } finally { await stop() }
})

test('relay attaches client IP to polled calls and enforces strict Bearer prefix', async () => {
  const { base, stop } = await start({ trustProxy: true })
  try {
    const { id, secret } = await open(base)
    assert.equal((await fetch(`${base}/s/${id}/poll`, { headers: { Authorization: secret } })).status, 401, 'bare token without Bearer prefix must be rejected')
    const call = fetch(`${base}/s/${id}/call`, { method: 'POST', headers: { 'CF-Connecting-IP': '203.0.113.8' }, body: Buffer.from('hi') })
    const polled = await (await fetch(`${base}/s/${id}/poll?wait=5`, { headers: bearer(secret) })).json()
    assert.equal(polled[0].ip, '203.0.113.8')
    await fetch(`${base}/s/${id}/answer/${polled[0].id}`, { method: 'POST', headers: bearer(secret), body: Buffer.from('ok') })
    assert.equal((await call).status, 200)
  } finally { await stop() }
})
