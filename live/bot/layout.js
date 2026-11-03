// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
bot.once('spawn', async () => {
  await wait(3000); bot.chat('/xl'); await wait(1500)
  const w = bot.currentWindow
  console.log('title', w && w.title)
  for (let i = 0; i < 27; i++) { const s = w.slots[i]; const n = s && s.customName; console.log(i, s ? (n && n.value ? n.value.text.value : n || s.name) : '-') }
  process.exit(0)
})
setTimeout(() => process.exit(0), 30000)
