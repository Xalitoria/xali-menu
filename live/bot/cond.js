// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const log = (...a) => console.log(...a)
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
bot.on('message', m => log('   CHAT', m.toString()))
async function show(label) {
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(400) }
  bot.chat('/xc'); await wait(1300)
  const w = bot.currentWindow; if (!w) return log(label, '!! no window')
  const names = []
  for (let i = 0; i < 54; i++) { const it = w.slots[i]; if (it) names.push(i + ':' + (it.customName ? (JSON.parse(JSON.stringify(it.customName)).value?.text?.value ?? '?') : it.name)) }
  log(label.padEnd(34), 'shown:', names.map(n => n.split(':')[0]).join(' '))
}
async function click(slot) { await bot.clickWindow(slot, 0, 0); await wait(800) }
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); con('clear Tester'); await wait(1000)
  await show('A start')
  con('give Tester diamond_sword 1'); await wait(800); await show('B holding a diamond sword')
  con('gamemode creative Tester'); await wait(500); await show('C creative')
  con('gamemode survival Tester'); await wait(500)
  con('xp add Tester 5 levels'); await wait(500); await show('D level 5, survival')
  await click(31); await click(30); log('   (health 4, food 2)'); await show('E after health 4 / food 2')
  await click(16); await show('F cooldown started (s15 gone)')
  await wait(4500); await show('G after the cooldown ended')
  await click(19); await show('H variable seen set')
  await click(32); await show('I once started (s17 gone for good)')
  await wait(6000); await show('J later: still gone')
  process.exit(0)
})
setTimeout(() => process.exit(0), 90000)
