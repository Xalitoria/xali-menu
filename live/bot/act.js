// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const log = (...a) => console.log(new Date().toISOString().slice(17, 23), ...a)
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
const watch = new Set(['set_title_text', 'set_title_subtitle', 'set_titles_time', 'boss_event', 'open_book', 'world_particles', 'level_particles', 'entity_effect'])
bot._client.on('packet', (d, m) => { if (watch.has(m.name)) log('PACKET', m.name, JSON.stringify(d).slice(0, 160)) })
bot.on('message', m => log('CHAT', m.toString()))
bot.on('windowOpen', w => log('WINDOW OPEN', JSON.stringify(w.title?.value ?? w.title)))
bot.on('windowClose', () => log('WINDOW CLOSE'))
const inv = () => bot.inventory.items().map(i => `${i.count}x${i.customName ? JSON.stringify(i.customName) : i.name}`).join(',') || '(empty)'
const state = () => log('STATE hp', bot.health, 'food', bot.food, 'xp lvl', bot.experience.level, 'mode', bot.game.gameMode, 'pos', bot.entity.position.toString(), 'inv', inv())
async function open() { bot.chat('/xt'); await wait(1200); if (!bot.currentWindow) log('!! menu did not open') }
async function click(slot, label) {
  if (!bot.currentWindow) await open()
  log('--- click', slot, label); try { await bot.clickWindow(slot, 0, 0) } catch (e) { log('click error', e.message) } await wait(900)
}
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); await wait(1000)
  await open()
  await click(1, 'take with no diamonds: expect no "took"'); state()
  await click(0, 'give 3'); state()
  await click(2, 'take named 1'); state()
  await click(1, 'take 2'); state()
  await click(0, 'give 3'); await click(14, 'clear diamonds'); state()
  await click(3, 'potion speed'); 
  await click(6, 'hurt: health -5, food 3'); state()
  await click(7, 'level +2'); state()
  await click(8, 'title'); await click(9, 'boss bar'); await wait(2500)
  await click(11, 'particles (second is invalid)')
  await click(13, 'run-for + random'); 
  await click(10, 'book'); await wait(500)
  await open()
  await click(4, 'teleport'); state()
  await click(5, 'gamemode creative'); state()
  await open()
  await click(12, 'input'); await wait(500)
  log('--- chat "abc" (invalid)'); bot.chat('abc'); await wait(900)
  log('--- chat "42"'); bot.chat('42'); await wait(1200)
  await open(); await click(12, 'input, wait for the timeout'); await wait(6000)
  await open(); await click(12, 'input, then cancel'); log('--- chat cancel'); bot.chat('cancel'); await wait(1200)
  log('--- chat hello (nothing waiting: ordinary chat)'); bot.chat('hello'); await wait(900)
  process.exit(0)
})
setTimeout(() => process.exit(0), 90000)
