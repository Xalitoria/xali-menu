// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh that has SkinsRestorer (XM_PLUGINS=SkinsRestorer).
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const stored = () => { const dir = SERVER + '/plugins/SkinsRestorer/players'; try { return fs.readdirSync(dir).map(f => f + ': ' + fs.readFileSync(dir + '/' + f, 'utf8').trim()).join(' | ') || '(none)' } catch (e) { return '(no folder)' } }
bot.once('spawn', async () => {
  await wait(3000)
  bot.on('message', m => console.log('chat:', m.toString()))
  bot.chat('/xs'); await wait(1500)
  console.log('before:', stored())
  await bot.clickWindow(0, 0, 0); await wait(5000); console.log('after set:', stored())
  await bot.clickWindow(1, 0, 0); await wait(3000); console.log('after reset:', stored())
  await bot.clickWindow(2, 0, 0); await wait(4000)
  process.exit(0)
})
setTimeout(() => process.exit(0), 50000)
