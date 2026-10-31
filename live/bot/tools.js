// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => { console.log('> ' + c); fs.writeFileSync(SERVER + '/in', c + '\n') }
bot.on('message', m => console.log('   CHAT', m.toString()))
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); await wait(500)
  con('gamemode survival Tester'); con('clear Tester'); con('xp set Tester 0 levels'); await wait(500)
  bot.chat('/xc'); await wait(1500)
  con('xmenu inspect Tester'); await wait(1500)
  con('xmenu info c'); con('xmenu info nothing'); await wait(500)
  con('xmenu validate'); await wait(500)
  con('xmenu validate i'); await wait(500)
  // change a file, reload only that one
  fs.appendFileSync(SERVER + '/plugins/XaliMenu/menus/c.conf', '\nitems.extra { material = stone, slot = 99 }\n')
  con('xmenu reload c'); await wait(800)
  fs.writeFileSync(SERVER + '/plugins/XaliMenu/menus/broken.conf', 'title = ')
  con('xmenu reload broken'); await wait(500)
  con('xmenu reload missing'); await wait(500)
  con('xmenu list'); await wait(500)
  process.exit(0)
})
setTimeout(() => process.exit(0), 40000)
