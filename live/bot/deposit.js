// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
// Slots that take an item (`accept`): from the cursor and by shift-click, what is refused, an abort that takes nothing.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const log = (...a) => console.log(new Date().toISOString().slice(17, 23), ...a)
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
bot.on('message', m => log('CHAT', m.toString()))
// The bot's item names are those of an older version, so only the counts are looked at.
const win = () => bot.currentWindow || bot.inventory
const counts = () => win().slots.map((i, n) => i && n >= 27 ? `${n >= 54 ? 'hotbar' + (n - 54) : 'inv' + n}:${i.count}` : null).filter(Boolean).join(' ') || '(empty)'
const cursor = () => win().selectedItem ? win().selectedItem.count : 0
const state = (label) => log(`STATE ${label}: cursor ${cursor()}; ${counts()}`)
async function click(slot, mode = 0, label = '') {
  try { await bot.clickWindow(slot, 0, mode) } catch (e) { log('click error', e.message) }
  await wait(700); state(`after click ${slot}${mode ? ' shift' : ''} ${label}`)
}
const HOT0 = 54 // window slot of the first hotbar slot, in a chest of 3 rows
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); await wait(500); con('clear Tester'); await wait(500); con('give Tester diamond 5'); await wait(800)
  bot.chat('/xd'); await wait(1200); if (!bot.currentWindow) log('!! menu did not open'); state('start')

  log('=== the cursor: 5 diamonds on slot 4 take 2 each time, the last 1 is refused')
  await click(HOT0, 0, 'pick up the diamonds')
  await click(4, 0, 'put on the gem slot'); await click(4, 0, 'again'); await click(4, 0, 'only 1 left: refused')
  await click(HOT0, 0, 'put the 1 back')

  log('=== another item is refused and stays')
  con('give Tester stone 3'); await wait(800); state('stone given')
  await click(HOT0 + 1, 0, 'pick up the stone'); await click(4, 0, 'put on the gem slot'); await click(HOT0 + 1, 0, 'put it back')

  log('=== a payment that aborts takes nothing')
  con('give Tester diamond 3'); await wait(800)
  await click(HOT0, 0, 'pick up the diamonds (hotbar 0 holds 1+3 = 4 now)'); await click(6, 0, 'put on the pay slot, no gold: nothing taken')
  con('give Tester gold_ingot 5'); await wait(800); state('5 gold given')
  await click(6, 0, 'pay slot with 5 gold now: the gold and 1 diamond go')
  await click(HOT0, 0, 'put the diamonds back')

  log('=== shift-click from the inventory')
  await click(HOT0, 1, 'shift-click the diamonds: 2 are taken')
  await click(HOT0 + 1, 1, 'shift-click the stone: the pay slot takes anything, but there is no gold left, so it aborts and the stone stays')

  log('=== the slot with nothing on the cursor, and a plain click')
  await click(4, 0, 'empty cursor on the gem slot'); await click(8, 0, 'the info item')
  bot.closeWindow(bot.currentWindow); await wait(800); state('closed')
  bot.end(); process.exit(0)
})
bot.on('error', e => log('ERR', e.message)); bot.on('kicked', r => log('KICKED', JSON.stringify(r)))
