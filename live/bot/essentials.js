// Part of the live tests of XaliMenu, see ../README.md. Needs a server set up with XM_ESSENTIALS=<EssentialsX jar> and `op Tester`.
// The extension adds catalogs (warps, homes, kits), conditions (state, home, kit) and actions (warp, home, sethome, delhome, kit, back, afk) for EssentialsX.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
let title = null
bot.on('windowOpen', w => { title = w.title ? JSON.stringify(w.title) : '?' })
bot.on('message', m => { const t = m.toString().trim(); if (t) console.log('  chat:', t) })
const show = label => {
  const w = bot.currentWindow
  console.log(label, title, w ? w.slots.slice(0, w.inventoryStart).map((s, i) => s ? `${i}:${s.customName ? JSON.stringify(s.customName) : s.name}` : null).filter(Boolean).join(' ') : '(no window)')
}
const open = async cmd => { title = null; bot.chat(cmd); await wait(1500) }
const click = async slot => { await bot.clickWindow(slot, 0, 0); await wait(1500) }
bot.once('spawn', async () => {
  await wait(4000)
  const step = process.argv[2] || 'all'
  console.log('--- warps (none yet)'); await open('/xesw'); show('warps:')
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await wait(300)
  console.log('--- setwarp, setwarp'); bot.chat('/setwarp alpha'); await wait(800); bot.chat('/setwarp beta'); await wait(800)
  await open('/xesw'); show('warps:')
  console.log('--- click first warp'); await click(0)
  console.log('--- hub: nothing set yet'); await open('/xes'); show('hub:')
  console.log('--- sethome base'); await click(0)
  await open('/xes'); show('hub after sethome:')
  console.log('--- afk, back'); await click(3); await open('/xes'); show('hub after afk:')
  await click(3); await wait(500)
  console.log('--- kit tools (hub)'); await open('/xes'); await click(4)
  await open('/xes'); show('hub after kit:')
  const pos = label => { const p = bot.entity.position; console.log(label, [p.x, p.y, p.z].map(v => Math.round(v)).join(' ')) }
  const where = async (x, z) => { bot.chat(`/tp Tester ${x} 120 ${z}`); await wait(2500) }
  console.log('--- kits menu: nothing may be said by opening it'); await open('/xesk'); show('kits:')
  const kitSlot = bot.currentWindow?.slots?.slice(0, bot.currentWindow.inventoryStart).find(s => s && JSON.stringify(s.customName || '').includes('tools'))
  console.log('tools kit lore:', JSON.stringify(kitSlot?.components?.find(c => c.type === 'lore') ?? kitSlot?.nbt ?? null))
  bot.closeWindow(bot.currentWindow); await wait(300)
  pos('start'); await where(60, 60); pos('moved')
  console.log('--- warp alpha from the menu: back to where it was set'); await open('/xesw'); await click(0); await wait(1500); pos('after warp')
  await where(60, 60)
  console.log('--- home base from the menu'); await open('/xesh'); await click(0); await wait(1500); pos('after home')
  console.log('--- back'); await open('/xes'); await click(2); await wait(1500); pos('after back')
  console.log('--- delete home with a right click'); await open('/xesh'); await bot.clickWindow(0, 1, 0); await wait(1500)
  await open('/xesh'); show('homes after delete:')
  console.log('--- a home that is not there'); await open('/xes'); await click(1); await wait(1000)
  process.exit(0)
})
setTimeout(() => process.exit(0), 90000)
