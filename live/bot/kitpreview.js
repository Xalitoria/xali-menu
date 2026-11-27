// Part of the live tests of XaliMenu, see ../README.md. Needs a server set up with XM_KITPREVIEW=<EssentialsX jar>.
// Tests EssentialsKitPreviewExtension: /kitpreview <kit>, dynamic @kitpreview:kits tab completion, missing-kit denial, and real ItemStack rendering.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
let title = null
bot.on('windowOpen', w => { title = w.title ? JSON.stringify(w.title) : '?' })
bot.on('message', m => { const t = m.toString().trim(); if (t) console.log('  chat:', t) })
const open = async cmd => { title = null; bot.chat(cmd); await wait(1500) }
const show = label => {
  const w = bot.currentWindow
  if (!w) { console.log(label, '(no window)'); return }
  const items = w.slots.slice(0, w.inventoryStart)
    .map((s, i) => (s && s.name !== 'gray_stained_glass_pane') ? `${i}:${s.name}x${s.count}${s.customName ? `(${JSON.stringify(s.customName)})` : ''}` : null)
    .filter(Boolean)
  console.log(label, title, items.join(' '))
}
bot.once('spawn', async () => {
  await wait(3500)
  console.log('--- tab complete')
  for (const line of ['/kitpreview ', '/kitpreview d', '/kitpreview fi']) {
    const found = await bot.tabComplete(line, false, false, 3000).catch(e => 'error ' + e.message)
    console.log(' ', JSON.stringify(line), '->', JSON.stringify(Array.isArray(found) ? found.map(m => m.match ?? m) : found))
  }
  console.log('--- missing kit')
  await open('/kitpreview nope')
  show('nope:')
  console.log('--- tools kit')
  await open('/kitpreview tools')
  show('tools:')
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await wait(300)
  console.log('--- dtools kit (enchants, custom names, lore, dyed leather, durability)')
  await open('/kitpreview dtools')
  show('dtools:')
  const pick = bot.currentWindow?.slots?.[10]
  console.log('  dtools[10] components:', JSON.stringify(pick?.components?.map(c => c.type) ?? null))
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await wait(300)
  console.log('--- firework kit (stack amount = 2 on third rocket)')
  await open('/kitpreview firework')
  show('firework:')
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  process.exit(0)
})
setTimeout(() => process.exit(0), 60000)
