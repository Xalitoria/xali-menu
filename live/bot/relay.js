// Part of the live tests of XaliMenu, see ../README.md. Needs the relay (`node menu/relay/relay.js`), a server set up with XM_RELAY=<its url>
// and started with ../server.sh, and Tester as an operator (`server.sh cmd op Tester`). From the copy in the server's folder, XM_WEB is menu/editor/web.
// Shares the editor with /xmenu share, then acts as the browser of the link (menu/editor/web/src/relay.js): reads the menus, saves one, which
// must be held; the console then lists, applies and drops edits, and the file is checked by the caller.
const mineflayer = require('mineflayer')
const path = require('path')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const chat = []
bot.on('message', (m) => { const text = m.toString(); chat.push(text); console.log('CHAT ' + text) })
const say = (line) => console.log('---- ' + line)
bot.once('spawn', async () => {
  const { RelayServer } = await import(path.resolve(process.env.XM_WEB || path.join(__dirname, '../../editor/web'), 'src/relay.js'))
  await wait(2000)
  bot.chat('/xmenu share')
  await wait(2500)
  const line = chat.find((c) => c.includes('#relay='))
  if (!line) { say('NO LINK'); process.exit(1) }
  const hash = new URLSearchParams(line.slice(line.indexOf('#relay=') + 1).split(/\s/)[0])
  const server = new RelayServer(hash.get('relay'), hash.get('session'), hash.get('key'), 'Alex')
  const info = await server.info()
  say('INFO shared=' + info.shared + ' menus=' + info.menus.join(',') + ' catalogs=' + Object.keys(info.catalogs).length)
  const name = info.menus[0]
  const menu = await server.menu(name)
  say('MENU ' + name + ' title=' + menu.title)
  menu.title = 'Changed through the relay'
  const problems = await server.save(name, menu)
  say('SAVE held=' + server.held + ' problems=' + problems.length)
  const bad = await server.save(name, { title: 1, size: 'x' }).catch((e) => 'REFUSED ' + e.message)
  say('BAD ' + bad)
  const glyphs = await server.glyphs().catch((e) => 'ERR ' + e.message)
  say('GLYPHS ' + JSON.stringify(glyphs))
  const pack = await server.pack()
  say('PACK ' + (pack ? pack.size : 'none'))
  console.log('READY ' + name)
  process.stdin.on('end', () => process.exit(0))
  setTimeout(() => process.exit(0), 120000)
})
