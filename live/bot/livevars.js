// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh, set up with PlaceholderAPI (it is copied from
// the dev server), and Tester as an operator. Opens /xv, a menu with no `refresh` that names variables, then changes the variables from the
// console without closing it: an item marked `update` must show the new value at once, one that is not must not.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
const text = (n) => { const j = JSON.parse(JSON.stringify(n)); const walk = (x) => typeof x === 'string' ? x : x?.value ? walk(x.value) : x?.text ? walk(x.text) : x?.extra ? walk(x.extra) : Array.isArray(x) ? x.map(walk).join('') : Array.isArray(x?.value) ? x.value.map(walk).join('') : (x?.type === 'string' ? x.value : ''); return walk(j) }
const shown = () => [0, 1, 2].map((i) => { const it = bot.currentWindow?.slots[i]; return i + '=' + (it ? (it.customName ? text(it.customName) : it.name) : '-') }).join(' | ')
const say = (label) => console.log('---- ' + label.padEnd(30) + shown())
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); await wait(1000)
  con('xvar rem coins'); con('xvarp Tester rem coins'); await wait(500)
  bot.chat('/xv'); await wait(1500)
  say('opened')
  con('xvar set coins 7'); await wait(600); say('global set to 7')
  con('xvarp Tester set coins 3'); await wait(600); say('personal set to 3')
  con('xvar inc coins 5'); await wait(600); say('global increased by 5')
  con('xvar set coins 7'); con('xvar set coins 8'); con('xvar set coins 9'); await wait(600); say('burst: 7, 8, 9')
  con('xvar rem coins'); await wait(600); say('removed')
  console.log('DONE'); process.exit(0)
})
setTimeout(() => process.exit(1), 60000)
