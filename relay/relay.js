// The relay for the web editor of XaliMenu: a mailbox that lets a browser talk to a server that only makes outbound connections.
//
// The server (the plugin) opens a session, then asks the relay for work (`poll`) and posts the answers. A browser puts a request in
// the session's mailbox (`call`) and waits for the answer. Everything in a request or an answer is encrypted by the two ends with a key
// that is only in the link's fragment, which the relay never sees; the relay moves bytes, keeps nothing after a call is answered, and
// forgets a session when the server stops asking for work or its time is up. Nothing is written to a disk.
//
//   POST   /s                     (Authorization: Bearer <create token>, when one is set)  ->  201 {id, secret}      body {ttl: seconds}
//   GET    /s/<id>/poll?wait=25   (Bearer <secret>)  ->  200 [{id, data}] with data as base64url, 204 when nothing came, 404 when gone
//   POST   /s/<id>/answer/<call>  (Bearer <secret>)  body: the encrypted answer  ->  204
//   POST   /s/<id>/call           body: the encrypted request  ->  200 the encrypted answer, 504 if the server did not answer, 404 if gone
//   DELETE /s/<id>                (Bearer <secret>)  ->  204
//
// Run it with `node relay.js`; PORT, HOST, XM_RELAY_TOKEN (to restrict who may open sessions) and XM_RELAY_ORIGINS (comma separated sites
// that may call it from a browser; default any, as the contents are encrypted) are read from the environment.

import { createServer } from 'node:http'
import { randomBytes, timingSafeEqual } from 'node:crypto'

const url64 = (bytes) => bytes.toString('base64url')

/**
 * @param {object} [options]
 * @param {string} [options.token] only a request with this token may open a session
 * @param {number} [options.maxBody] the largest message, in bytes
 * @param {number} [options.maxSessions]
 * @param {number} [options.maxTtl] the longest a session may live, in seconds
 * @param {number} [options.idle] seconds without a poll after which a session is forgotten
 * @param {number} [options.callTimeout] seconds a call waits for its answer
 * @param {number} [options.pollWait] the longest a poll waits, in seconds
 * @param {string[]|null} [options.origins] sites allowed to call from a browser, or null for any
 */
export function createRelay(options = {}) {
  const {
    token = '', maxBody = 8 * 1024 * 1024, maxSessions = 1000, maxTtl = 4 * 3600, idle = 120, callTimeout = 30, pollWait = 25, origins = null,
  } = options
  /** @type {Map<string, {secret: Buffer, expires: number, seen: number, queue: object[], waiting: Map<string, object>, poller: object|null}>} */
  const sessions = new Map()

  const sweep = () => {
    const now = Date.now()
    for (const [id, session] of sessions) {
      if (session.expires < now || session.seen + idle * 1000 < now) end(id)
    }
  }
  const timer = setInterval(sweep, 5000)
  timer.unref()

  function end(id) {
    const session = sessions.get(id)
    if (!session) return
    sessions.delete(id)
    for (const call of session.waiting.values()) { clearTimeout(call.timer); call.answer(404, { error: 'The session has ended' }) }
    if (session.poller) { clearTimeout(session.poller.timer); send(session.poller.res, 404, { error: 'The session has ended' }) }
  }

  function send(res, status, body, headers = {}) {
    if (res.writableEnded) return
    if (body === undefined || body === null) { res.writeHead(status, headers); res.end(); return }
    if (Buffer.isBuffer(body)) { res.writeHead(status, { 'Content-Type': 'application/octet-stream', ...headers }); res.end(body); return }
    res.writeHead(status, { 'Content-Type': 'application/json', ...headers })
    res.end(JSON.stringify(body))
  }

  function readBody(req, res) {
    return new Promise((resolve) => {
      const chunks = []
      let size = 0
      req.on('data', (chunk) => {
        size += chunk.length
        if (size > maxBody) { send(res, 413, { error: 'The message is too big' }); req.destroy(); resolve(null) } else chunks.push(chunk)
      })
      req.on('end', () => resolve(Buffer.concat(chunks)))
      req.on('error', () => resolve(null))
    })
  }

  function authorized(session, req) {
    const given = Buffer.from((req.headers.authorization ?? '').replace(/^Bearer /, ''))
    return given.length === session.secret.length && timingSafeEqual(given, session.secret)
  }

  /** Hands the oldest waiting call to a poll that is waiting, or answers the poll with what is queued. */
  function deliver(session) {
    if (!session.poller || session.queue.length === 0) return
    const { res, timer } = session.poller
    clearTimeout(timer)
    session.poller = null
    const batch = session.queue.splice(0, 16)
    send(res, 200, batch.map((call) => ({ id: call.id, data: url64(call.data) })))
  }

  async function handle(req, res) {
    const origin = req.headers.origin
    const allowed = origin && (origins === null || origins.includes(origin))
    const cors = allowed ? { 'Access-Control-Allow-Origin': origins === null ? '*' : origin, Vary: 'Origin' } : {}
    const reply = (status, body, headers = {}) => send(res, status, body, { 'Cache-Control': 'no-store', ...cors, ...headers })
    if (origin && !allowed) return reply(403, { error: 'This site may not use the relay' })
    if (req.method === 'OPTIONS') {
      return reply(204, null, { 'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Max-Age': '600' })
    }
    const parts = new URL(req.url, 'http://relay').pathname.split('/').filter(Boolean)
    const query = new URL(req.url, 'http://relay').searchParams
    if (parts[0] !== 's') return reply(404, { error: 'Unknown request' })

    if (parts.length === 1 && req.method === 'POST') {
      const given = Buffer.from((req.headers.authorization ?? '').replace(/^Bearer /, ''))
      const wanted = Buffer.from(token)
      if (token && !(given.length === wanted.length && timingSafeEqual(given, wanted))) return reply(401, { error: 'A token is needed to open a session' })
      sweep()
      if (sessions.size >= maxSessions) return reply(503, { error: 'The relay is full' })
      const body = await readBody(req, res)
      if (body === null) return
      let ttl = 3600
      try { ttl = Number(JSON.parse(body.toString() || '{}').ttl) || ttl } catch { /* the default */ }
      ttl = Math.min(Math.max(ttl, 60), maxTtl)
      const id = url64(randomBytes(16))
      const secret = url64(randomBytes(24))
      sessions.set(id, { secret: Buffer.from(secret), expires: Date.now() + ttl * 1000, seen: Date.now(), queue: [], waiting: new Map(), poller: null })
      return reply(201, { id, secret, ttl })
    }

    const id = parts[1]
    const session = sessions.get(id)
    if (!session) return reply(404, { error: 'There is no such session, or it has ended' })

    if (parts.length === 2 && req.method === 'DELETE') {
      if (!authorized(session, req)) return reply(401, { error: 'Not the secret of this session' })
      end(id)
      return reply(204, null)
    }
    if (parts.length === 3 && parts[2] === 'poll' && req.method === 'GET') {
      if (!authorized(session, req)) return reply(401, { error: 'Not the secret of this session' })
      session.seen = Date.now()
      if (session.poller) { clearTimeout(session.poller.timer); send(session.poller.res, 204, null) }
      const wait = Math.min(Math.max(Number(query.get('wait')) || 0, 0), pollWait)
      if (session.queue.length === 0 && wait === 0) return reply(204, null)
      if (session.queue.length > 0) {
        session.poller = { res, timer: null }
        return deliver(session)
      }
      const timer = setTimeout(() => { if (session.poller?.res === res) { session.poller = null; session.seen = Date.now(); reply(204, null) } }, wait * 1000)
      session.poller = { res, timer }
      res.on('close', () => { if (session.poller?.res === res) { clearTimeout(timer); session.poller = null } })
      return
    }
    if (parts.length === 3 && parts[2] === 'call' && req.method === 'POST') {
      if (session.queue.length + session.waiting.size >= 64) return reply(429, { error: 'Too many requests are waiting' })
      const data = await readBody(req, res)
      if (data === null) return
      const call = { id: url64(randomBytes(9)), data, timer: null, answer: (status, body) => reply(status, body) }
      const drop = () => { clearTimeout(call.timer); session.waiting.delete(call.id); const at = session.queue.indexOf(call); if (at >= 0) session.queue.splice(at, 1) }
      call.timer = setTimeout(() => { drop(); reply(504, { error: 'The server did not answer' }) }, callTimeout * 1000)
      session.waiting.set(call.id, call)
      res.on('close', drop)
      session.queue.push(call)
      return deliver(session)
    }
    if (parts.length === 4 && parts[2] === 'answer' && req.method === 'POST') {
      if (!authorized(session, req)) return reply(401, { error: 'Not the secret of this session' })
      const data = await readBody(req, res)
      if (data === null) return
      const call = session.waiting.get(parts[3])
      if (call) { clearTimeout(call.timer); session.waiting.delete(call.id); call.answer(200, data) }
      return reply(204, null)
    }
    return reply(404, { error: 'Unknown request' })
  }

  const server = createServer((req, res) => { handle(req, res).catch((error) => send(res, 500, { error: String(error?.message ?? error) })) })
  server.on('close', () => { clearInterval(timer); for (const id of [...sessions.keys()]) end(id) })
  server.sessions = sessions
  return server
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const origins = process.env.XM_RELAY_ORIGINS ? process.env.XM_RELAY_ORIGINS.split(',').map((s) => s.trim()) : null
  const server = createRelay({ token: process.env.XM_RELAY_TOKEN ?? '', origins })
  const port = Number(process.env.PORT ?? 8787)
  server.listen(port, process.env.HOST ?? '127.0.0.1', () => console.log(`XaliMenu relay on port ${port}`))
}
