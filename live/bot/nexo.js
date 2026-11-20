// Part of the live tests of XaliMenu, see ../README.md. Needs Nexo in the server's plugins (XM_PLUGINS="nexo" at setup, or copy its jar and folder) with its pack
// not sent before joining (a bot cannot accept it: settings.yml `pack.dispatch.send_pre_join: false` and `mandatory: false`), and the menu `nx` (live/menus/nx.conf).
// Opens /xnx and prints what the slots hold: items of Nexo (`nexo:earth_wand`) with their name and count, an item that does not exist (left out), and a vanilla one;
// then clicks the first and prints the chat.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
bot.on('message', (m) => console.log('CHAT ' + m.toString()))
bot.once('spawn', async () => {
  await wait(4000)
  bot.chat('/xnx')
  await wait(2500)
  const window = bot.currentWindow
  if (!window) { console.log('NO WINDOW'); process.exit(1) }
  console.log('TITLE ' + JSON.stringify(window.title))
  for (const slot of window.slots.slice(0, 27)) {
    if (!slot) continue
    const keys = slot.components ? slot.components.map((c) => c.type).join(',') : ''
    console.log(`SLOT ${slot.slot} ${slot.name} x${slot.count} name=${JSON.stringify(slot.customName ?? slot.displayName)} components=[${keys}]`)
  }
  await bot.clickWindow(0, 0, 0)
  await wait(800)
  console.log('DONE')
  process.exit(0)
})
setTimeout(() => process.exit(2), 60000)
