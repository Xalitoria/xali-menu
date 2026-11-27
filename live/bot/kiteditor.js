// Part of the live tests of XaliMenu, see ../README.md. Needs XM_KITPREVIEW=<jar> XM_KITEDITOR=<jar> and `op Tester`.
// Tests EssentialsKitEditorExtension + EssentialsKitPreviewExtension: /kits selector, /showkit alias, /ekits untouched,
// /kiteditor <kit> (tab complete, add held item, remove item, copy item, preview, delete), and live persistence.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
let title = null
bot.on('windowOpen', w => { title = w.title ? JSON.stringify(w.title) : '?' })
bot.on('message', m => { const t = m.toString().trim(); if (t) console.log('  chat:', t) })
const open = async cmd => { title = null; bot.chat(cmd); await wait(1500) }
const click = async (slot, button = 0) => { await bot.clickWindow(slot, button, 0); await wait(1500) }
const namedSlots = () => {
  const w = bot.currentWindow
  if (!w) return '(no window)'
  return w.slots.slice(0, w.inventoryStart)
    .map((s, i) => {
      if (!s) return null
      const name = s.customName ? JSON.stringify(s.customName) : ''
      if (name.includes('"value":" "')) return null
      return `${i}:x${s.count}${name ? `(${name})` : ''}`
    })
    .filter(Boolean).join(' ')
}
bot.once('spawn', async () => {
  await wait(3500)
  const mode = process.argv[2] || 'admin'
  if (mode === 'nonop') {
    console.log('--- non-op tab complete /kiteditor & /kitpreview')
    for (const line of ['/kiteditor ', '/editkit ', '/kitpreview ', '/showkit ']) {
      const found = await bot.tabComplete(line, false, false, 3000).catch(e => 'error ' + e.message)
      console.log(' ', JSON.stringify(line), '->', JSON.stringify(Array.isArray(found) ? found.map(m => m.match ?? m) : found))
    }
    console.log('--- non-op /kiteditor tools (must be denied)')
    await open('/kiteditor tools')
    console.log('  kiteditor window:', title)
    console.log('--- non-op /kitpreview tools (denied without essentials.showkit)')
    await open('/kitpreview tools')
    console.log('  kitpreview window:', title)
    console.log('--- non-op /kits (allowed via player-commands, Create button hidden)')
    await open('/kits')
    console.log('  kits title:', title)
    console.log('  kits slots:', namedSlots())
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    process.exit(0)
  }
  console.log('--- tab complete /kiteditor, /editkit, /showkit')
  for (const line of ['/kiteditor ', '/editkit d', '/showkit t']) {
    const found = await bot.tabComplete(line, false, false, 3000).catch(e => 'error ' + e.message)
    console.log(' ', JSON.stringify(line), '->', JSON.stringify(Array.isArray(found) ? found.map(m => m.match ?? m) : found))
  }
  console.log('--- /ekits stays EssentialsX chat command (no window)')
  await open('/ekits')
  console.log('  ekits window:', title)
  console.log('--- /kits selector menu')
  await open('/kits')
  console.log('  kits title:', title)
  console.log('  kits slots:', namedSlots())
  console.log('--- right-click tools kit (slot 14) -> opens kitpreview')
  await click(14, 1)
  console.log('  preview title:', title)
  console.log('--- click Edit Kit (slot 44) -> opens kiteditor tools')
  await click(44, 0)
  console.log('  editor title:', title)
  console.log('  editor slots before add:', namedSlots())
  const delaySlot = bot.currentWindow?.slots?.[40]
  console.log('  delay lore:', JSON.stringify(delaySlot?.components?.find(c => c.type === 'lore') ?? null))
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await wait(300)

  console.log('--- create a scratch kit via /createkit scratch 30, edit it in /kiteditor scratch, then delete it')
  bot.chat('/give Tester diamond 3'); await wait(800)
  bot.chat('/createkit scratch 30'); await wait(1000)
  await open('/kiteditor scratch')
  console.log('  scratch editor before add-held:', namedSlots())
  console.log('--- click Add Held Item (slot 37)')
  await click(37, 0)
  console.log('  scratch editor after add-held:', namedSlots())
  console.log('--- right-click first item (slot 10) to remove it')
  await click(10, 1)
  console.log('  scratch editor after remove:', namedSlots())
  console.log('--- click Delete Kit (slot 43) -> deletes kit and returns to /kits')
  await click(43, 0)
  console.log('  after delete title:', title)
  console.log('  after delete slots:', namedSlots())
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  process.exit(0)
})
setTimeout(() => process.exit(0), 90000)
