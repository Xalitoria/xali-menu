// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh and XM_ADDON=1 at setup.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
let title = null
bot.on('windowOpen', w => { title = w.title ? JSON.stringify(w.title) : '?'; })
bot.on('message', m => { const t = m.toString(); if (t.startsWith('fired')) console.log('  chat:', t) })
bot.once('spawn', async () => {
  await wait(3000)
  for (const line of ['/xmwave wave 1', '/xmwave wave 3', '/xmwave bow', '/xmwave dance']) {
    title = null
    bot.chat(line); await wait(1500)
    console.log(line, '-> window:', title)
    if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(500) }
  }
  process.exit(0)
})
setTimeout(() => process.exit(0), 30000)
