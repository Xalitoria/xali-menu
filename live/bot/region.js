// Part of the live tests of XaliMenu, see ../README.md. Needs a server with WorldGuard and WorldEdit (XM_PLUGINS="FastAsyncWorldEdit worldguard", on Paper).
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
const name = s => s && (s.customName && s.customName.value ? s.customName.value.text.value : s.name)
const window = () => { const w = bot.currentWindow; if (!w) return 'no menu'; const t = w.title && w.title.value ? JSON.stringify(w.title.value.text ? w.title.value.text.value : w.title) : String(w.title); return t + ' ' + w.slots.slice(0, 9).map(name).map(n => n || '.').join('|') }
const step = async (label, command) => { if (command) con(command); await wait(2500); console.log(label.padEnd(34), window()); if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(500) } }
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); con('gamemode creative Tester'); await wait(1000)
  bot.chat('//pos1 10,-60,10'); await wait(1500); bot.chat('//pos2 20,300,20'); await wait(1500); bot.chat('/rg define zone'); await wait(2500)
  bot.chat('/xr here'); await wait(2000); console.log('/xr here'.padEnd(34), window()); if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(500) }
  await step('enter zone', 'tp Tester 15 100 15')
  await step('move inside zone', 'tp Tester 17 100 17')
  await step('leave zone', 'tp Tester -45 100 -39')
  bot.chat('/xr there'); await wait(2000); console.log('/xr there'.padEnd(34), window())
  process.exit(0)
})
setTimeout(() => process.exit(0), 60000)
