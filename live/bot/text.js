// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
// The text input: an anvil (what an older client gets) and a dialog (what a newer one gets).
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const log = (...a) => console.log(new Date().toISOString().slice(17, 23), ...a)
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
let dialog = null, lastDialog = null
bot._client.on('packet', (d, m) => {
  if (m.name === 'show_dialog') { dialog = lastDialog = d; log('PACKET show_dialog', JSON.stringify(d).slice(0, 900)) }
  if (m.name === 'clear_dialog') log('PACKET clear_dialog')
})
bot.on('message', m => log('CHAT', m.toString()))
bot.on('windowOpen', w => log('WINDOW OPEN', w.type, JSON.stringify(w.title?.value ?? w.title), 'slots', w.slots.map((i, n) => i && `${n}:${i.name}${i.customName ? JSON.stringify(i.customName) : ''}`).filter(Boolean).join(' ')))
bot.on('windowClose', () => log('WINDOW CLOSE'))
const items = () => bot.inventory.items().map(i => `${i.count}x${i.name}`).join(',') || '(empty)'
async function open() { bot.chat('/xu'); await wait(1200); if (!bot.currentWindow) log('!! menu did not open') }
async function rename(text) { bot._client.write('name_item', { name: text }); await wait(700) }
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); await wait(1000)
  log('--- inventory before', items())

  log('=== anvil: invalid, then valid')
  await open(); await bot.clickWindow(1, 0, 0); await wait(1200)
  const anvil = bot.currentWindow
  log('anvil window', anvil && anvil.type)
  await rename('Bad Name 1'); log('result slot', JSON.stringify(anvil.slots[2] && anvil.slots[2].customName))
  try { await bot.clickWindow(2, 0, 0) } catch (e) { log('click error', e.message) } await wait(700)
  log('still open after invalid?', !!bot.currentWindow)
  await rename('castle'); log('result slot', JSON.stringify(anvil.slots[2] && anvil.slots[2].customName))
  try { await bot.clickWindow(2, 0, 0) } catch (e) { log('click error', e.message) } await wait(1000)
  log('closed after valid?', !bot.currentWindow, 'inventory', items(), 'cursor', JSON.stringify(bot.inventory.selectedItem && bot.inventory.selectedItem.name))

  log('=== anvil: shift-click the paper and take it out, nothing may arrive')
  await open(); await bot.clickWindow(1, 0, 0); await wait(1200)
  try { await bot.clickWindow(0, 0, 1) } catch (e) { log('click error', e.message) } await wait(500)
  try { await bot.clickWindow(0, 0, 0) } catch (e) { log('click error', e.message) } await wait(500)
  log('inventory while open', items(), 'cursor', JSON.stringify(bot.inventory.selectedItem && bot.inventory.selectedItem.name))
  log('=== anvil: leave it (Esc) = cancel'); bot.closeWindow(bot.currentWindow); await wait(1000)
  log('inventory after', items(), 'cursor', JSON.stringify(bot.inventory.selectedItem && bot.inventory.selectedItem.name))

  log('=== anvil: timeout')
  await open(); await bot.clickWindow(1, 0, 0); await wait(9500); log('still open after timeout?', !!bot.currentWindow)

  log('=== dialog: invalid, valid, cancel button, timeout')
  const press = async (which, text) => {
    const button = lastDialog.dialog.data.value[which].value.action.value
    const payload = { type: 'compound', value: { ...(button.additions ? button.additions.value : {}), text: { type: 'string', value: text } } }
    dialog = null; log('send click', which, JSON.stringify(text), button.id.value)
    // The server (26.2) length-prefixes the payload and has no boolean before the tag, which the 26.1 protocol data the bot has does not know.
    const full = bot._client.serializer.createPacketBuffer({ name: 'custom_click_action', params: { id: button.id.value, nbt: payload } })
    const head = 2 + Buffer.byteLength(button.id.value), tail = full.subarray(head + 1) // and no boolean before the tag
    bot._client.writeRaw(Buffer.concat([full.subarray(0, head), Buffer.from([tail.length]), tail])); await wait(1200)
  }
  dialog = null; await open(); await bot.clickWindow(0, 0, 0); await wait(1500)
  log('dialog shown?', !!dialog, 'title', JSON.stringify(dialog && dialog.dialog.data.value.title))
  log('field', JSON.stringify(dialog.dialog.data.value.inputs.value.value[0]))
  await press('yes', 'Not Valid 1')
  log('shown again with the reason?', JSON.stringify(dialog && dialog.dialog.data.value.body || null).slice(0, 120))
  await press('yes', 'tower')
  log('replayed callback does nothing'); await press('yes', 'second')
  dialog = null; await open(); await bot.clickWindow(0, 0, 0); await wait(1500)
  await press('no', '')
  dialog = null; await open(); await bot.clickWindow(0, 0, 0); await wait(9500)
  await wait(500)
  con('save-all'); await wait(300)
  log('--- final inventory', items())
  bot.end(); process.exit(0)
})
bot.on('error', e => log('ERR', e.message)); bot.on('kicked', r => log('KICKED', JSON.stringify(r)))
