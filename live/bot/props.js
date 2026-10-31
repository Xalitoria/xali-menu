// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); con('clear Tester'); await wait(800)
  bot.chat('/xi'); await wait(1300); await bot.clickWindow(0, 0, 0); await wait(1000)
  con('data get entity Tester Inventory'); await wait(800)
  process.exit(0)
})
setTimeout(() => process.exit(0), 40000)
