// Part of the live tests of XaliMenu, see ../README.md. Opens /xp, a menu that draws in the player's own inventory (menus/p.conf), over
// items given first. Shows what is where, that a click in a drawn slot runs its action and one anywhere else moves nothing, that an
// `update` item follows a variable, and that closing the menu brings the items back. The items only exist in packets, so the server's own
// inventory must never hold them.
//   node bot/inventory.js                 the whole run
//   XM_MODE=saved node bot/inventory.js   leaves the menu open with the player's data saved (/xmsave, from XM_ADDON=1): then read it with
//                                         python3 bot/nbt.py <server>/world/players/data/<uuid>.dat: the real items, not the menu's
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const MODE = process.env.XM_MODE || 'run'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
const text = (n) => { const j = JSON.parse(JSON.stringify(n)); const walk = (x) => typeof x === 'string' ? x : x?.value ? walk(x.value) : x?.text ? walk(x.text) : x?.extra ? walk(x.extra) : Array.isArray(x) ? x.map(walk).join('') : Array.isArray(x?.value) ? x.value.map(walk).join('') : (x?.type === 'string' ? x.value : ''); return walk(j) }
const label = (it) => it ? (it.customName ? text(it.customName) : it.name) + (it.count > 1 ? ' x' + it.count : '') : '-'
// Window numbering is the same in a 9-slot window and the inventory: 9 to 35 the rows, 36 to 44 the hotbar.
const SLOTS = { 'hotbar 0': 36, 'hotbar 4': 40, 'inventory 0': 9, 'inventory 1': 10 }
const show = (label_, from) => console.log('---- ' + label_.padEnd(28) + Object.entries(SLOTS).map(([n, i]) => n + '=' + label(from.slots[i])).join(' | '))
const chat = []
bot.on('messagePlayer', () => {}); bot.on('message', (m) => chat.push(m.toString()))
bot.once('spawn', async () => {
  await wait(3000)
  con('op Tester'); await wait(1000)
  con('xvar rem coins'); await wait(300)
  con('clear Tester'); await wait(300)
  // `give` fills the hotbar first (0 to 8), then the rows from 9: so 5 more things come after the 5 of the hotbar's first slots.
  for (const g of ['diamond 5', 'dirt 1', 'sand 1', 'gravel 1', 'apple 7', 'poppy 1', 'dandelion 1', 'allium 1', 'azure_bluet 1', 'emerald 2', 'cobblestone 9']) { con('give Tester ' + g); await wait(150) }
  await wait(1000)
  show('before', bot.inventory)
  bot.chat('/xp'); await wait(1500)
  show('menu open', bot.currentWindow)
  if (MODE === 'saved') { bot.chat('/xmsave'); await wait(2000); console.log('OPEN, saved'); await wait(30000); process.exit(0) }
  chat.length = 0
  await bot.clickWindow(40, 0, 0); await wait(500)
  console.log('---- click on Home               chat: ' + JSON.stringify(chat.filter((m) => /home/.test(m))))
  await bot.clickWindow(36, 0, 0); await wait(300)
  await bot.clickWindow(36, 0, 1); await wait(300)   // shift-click on the diamonds
  await bot.clickWindow(37, 0, 0); await wait(300)   // dirt
  show('after clicks elsewhere', bot.currentWindow)
  con('xvar set coins 7'); await wait(700); show('coins = 7', bot.currentWindow)
  con('xvar set coins 8'); await wait(700); show('coins = 8', bot.currentWindow)
  con('give Tester stick 3'); await wait(700); console.log('---- picked up/given while open   (give goes straight in: ' + label(bot.currentWindow.slots[37]) + ')')
  con('gamemode creative Tester'); con('xvar set coins 9'); await wait(900); show('creative, coins = 9', bot.currentWindow)
  con('gamemode survival Tester'); con('xvar set coins 10'); await wait(900); show('survival, coins = 10', bot.currentWindow)
  bot.closeWindow(bot.currentWindow); await wait(1200)
  show('closed', bot.inventory)
  console.log('DONE'); process.exit(0)
})
setTimeout(() => process.exit(1), 90000)
