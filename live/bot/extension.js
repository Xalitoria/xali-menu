// Part of the live tests of XaliMenu, see ../README.md. Needs a server set up with XM_EXTENSION=1 (and PlaceholderAPI, which setup copies).
// Joining opens the menu through the extension's `demo:join` activator (a listener of the extension); its items follow the extension's condition, which asks PlaceholderAPI
// (no expansion is installed, so an unknown placeholder stays as it was written: the first item is shown, the second is not).
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
let title = null
bot.on('windowOpen', w => { title = w.title ? JSON.stringify(w.title) : '?' })
bot.on('message', m => { const t = m.toString(); if (t.startsWith('said')) console.log('  chat:', t) })
bot.once('spawn', async () => {
  await wait(4000)
  console.log('window after joining:', title)
  const w = bot.currentWindow
  if (w) {
    for (const slot of [0, 1]) console.log('slot', slot, '->', w.slots[slot] ? w.slots[slot].name : 'empty')
    await bot.clickWindow(0, 0, 0)
    await wait(1000)
  }
  process.exit(0)
})
setTimeout(() => process.exit(0), 30000)
