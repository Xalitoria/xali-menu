// Part of the live tests of XaliMenu, see ../README.md. Needs the relay (`node menu/relay/relay.js`), a server set up with XM_RELAY=<its url>
// and started with ../server.sh, and Tester as an operator (`server.sh cmd op Tester`). From the copy in the server's folder, XM_WEB is menu/editor/web.
// Shares the editor with /xmenu share, then acts as the browser of the link (menu/editor/web/src/relay.js): reads the menus, saves one, which
// must be held (or, with XM_READONLY=1 and /xmenu share readonly, refused); the console then lists, applies and drops edits, and the file is checked by the caller.
const mineflayer = require('mineflayer')
const path = require('path')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const chat = []
bot.on('message', (m) => { const text = m.toString(); chat.push(text); console.log('CHAT ' + text) })
const say = (line) => console.log('---- ' + line)
bot.once('spawn', async () => {
  const { RelayServer } = await import(path.resolve(process.env.XM_WEB || path.join(__dirname, '../../editor/web'), 'src/relay.js'))
  const { fromHash, defaultRelay } = await import(path.resolve(process.env.XM_WEB || path.join(__dirname, '../../editor/web'), 'src/source.js'))
  await wait(2000)
  bot.chat(process.env.XM_READONLY ? '/xmenu share readonly' : '/xmenu share')
  await wait(2500)
  const line = chat.find((c) => c.includes('#'))
  if (!line) { say('NO LINK'); process.exit(1) }
  const fragment = line.slice(line.indexOf('#')).split(/\s/)[0]
  const parsed = fromHash(fragment)
  const server = parsed.code
    ? RelayServer.fromCode(parsed.relay || defaultRelay(), parsed.code, 'Alex')
    : new RelayServer(parsed.relay, parsed.session, parsed.key, 'Alex')
  const info = await server.info()
  say('INFO shared=' + info.shared + ' readOnly=' + info.readOnly + ' menus=' + info.menus.join(',') + ' catalogs=' + Object.keys(info.catalogs).length)
  const name = info.menus[0]
  const menu = await server.menu(name)
  say('MENU ' + name + ' title=' + menu.title)
  menu.title = 'Changed through the relay'
  const problems = await server.save(name, menu).catch((e) => 'REFUSED ' + e.message)
  say('SAVE held=' + server.held + ' problems=' + (typeof problems === 'string' ? problems : problems.length))
  const bad = await server.save(name, { title: 1, size: 'x' }).catch((e) => 'REFUSED ' + e.message)
  say('BAD ' + bad)
  const glyphs = await server.glyphs().catch((e) => 'ERR ' + e.message)
  say('GLYPHS ' + JSON.stringify(glyphs))
  say('PACKINFO size=' + info.packSize + ' url=' + info.packUrl + ' pack=' + info.pack)
  const { Zip } = await import(path.resolve(process.env.XM_WEB || path.join(__dirname, '../../editor/web'), 'src/zip.js'))
  const file = await server.packFile(info.packSize)
  if (file) {
    const zip = await Zip.open(file)
    const names = zip.list()
    const small = names.find((n) => n.endsWith('.json'))
    say('PACK ' + file.size + ' bytes, ' + names.length + ' entries; ' + small + ' = ' + (await zip.text(small)).slice(0, 40) + '; blocks fetched ' + (file.blocks?.size ?? 'all'))
  } else say('PACK none')
  console.log('READY ' + name)
  process.stdin.on('end', () => process.exit(0))
  setTimeout(() => process.exit(0), 120000)
})
