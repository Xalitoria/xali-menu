// Part of the live tests of XaliMenu, see ../README.md. Needs a server with DeluxeMenus (XM_PLUGINS=DeluxeMenus, on Paper: XM_PAPER=<jar>) whose
// menus have been imported with `xmenu import deluxemenus`. Opens each menu with DeluxeMenus and with XaliMenu and prints what differs.
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
const menus = (process.env.XM_MENUS || 'basics_menu,advanced_menu,requirements_menu').split(',')
const sorted = v => Array.isArray(v) ? v.map(sorted) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k, sorted(v[k])])) : v
const text = v => JSON.stringify(sorted(v), (k, x) => typeof x === 'bigint' ? Number(x) : x)
// mineflayer gives NBT as {type, value}; this turns it into plain objects, a text component into styled runs, and joins runs of one style.
const plain = n => { if (!n || typeof n !== 'object' || !('type' in n)) return n; if (n.type === 'compound') return Object.fromEntries(Object.entries(n.value).map(([k, v]) => [k, plain(v)])); if (n.type === 'list') return (n.value.value || []).map(v => plain({ type: n.value.type, value: v })); return n.value }
const STYLES = ['bold', 'italic', 'underlined', 'strikethrough', 'obfuscated']
const runs = (component, inherited, out) => {
  if (component && typeof component === 'object' && '' in component) component = component['']  // a string among compounds in a list is sent as { '': text }
  if (typeof component === 'string') { if (component) out.push({ text: component, ...inherited }); return out }
  const style = { ...inherited }
  if (component.color) style.color = component.color
  for (const k of STYLES) if (k in component) style[k] = !!component[k]
  if (component.text) out.push({ text: component.text, ...style })
  for (const child of component.extra || []) runs(child, style, out)
  return out
}
const line = component => runs(plain(component), { italic: true }, []).reduce((all, r) => {
  const last = all[all.length - 1]
  if (last && STYLES.concat('color').every(k => !!last[k] === !!r[k] && (k !== 'color' || last[k] === r[k]))) last.text += r.text; else all.push({ ...r })
  return all
}, []).map(r => (r.color || '-') + (STYLES.filter(k => r[k]).map(k => k[0]).join('')) + ':' + r.text).join('')
const blank = v => typeof v === 'string' && /^[-a-z_]*[a-z]*:\s*$/.test(v)
const describe = components => {
  const out = {}
  for (const c of components || []) {
    if (c.type === 'custom_data') continue
    if (c.type === 'custom_name' || c.type === 'item_name') out[c.type] = blank(line(c.data)) ? '(blank)' : line(c.data)
    else if (c.type === 'lore') out.lore = (c.data.value ? c.data.value.value : c.data).map(l => line({ type: 'compound', value: l.value !== undefined && l.type ? l.value : l }))
    else out[c.type] = text(plain(c.data))
  }
  return out
}
const snapshot = () => {
  const w = bot.currentWindow
  if (!w) return null
  const slots = {}
  w.slots.slice(0, w.inventoryStart).forEach((s, i) => { if (s) slots[i] = { item: s.name, count: s.count, ...describe(s.components) } })
  return { title: line(w.title), slots }
}
// What is clicked in a menu, when it has an entry here: [slot, button, mode] as in a window click packet.
const CLICKS = { compare_menu: [[0, 0, 0], [0, 1, 0], [0, 0, 1], [0, 1, 1], [0, 2, 3], [29, 0, 0], [30, 0, 0]] }
let chat = []
bot.on('messagestr', m => { if (!m.startsWith('Tester issued')) chat.push(m) })
const open = async (command, name) => {
  chat = []
  bot.chat(command); await wait(1800)
  const s = snapshot()
  for (const [slot, button, mode] of CLICKS[name] || []) {
    if (!bot.currentWindow) break
    chat.push('click ' + [slot, button, mode]); await bot.clickWindow(slot, button, mode); await wait(700)
  }
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await wait(900)
  return s && { ...s, chat }
}
bot.once('spawn', async () => {
  await wait(3000); con(process.env.XM_OP === 'false' ? 'deop Tester' : 'op Tester'); await wait(1200)
  for (const name of menus) {
    const dm = await open('/dm open ' + name, name), xm = await open('/xmenu open ' + name, name)
    console.log('==', name)
    if (!dm || !xm) { console.log('  not opened:', dm ? '' : 'DeluxeMenus', xm ? '' : 'XaliMenu'); continue }
    if (text(dm.chat) !== text(xm.chat)) { console.log('  chat DIFFERS'); console.log('    deluxemenus:', text(dm.chat)); console.log('    xalimenu   :', text(xm.chat)) } else if (dm.chat.length) console.log('  chat same:', text(dm.chat))
    if (dm.title !== xm.title) console.log('  title', dm.title, '|', xm.title)
    for (const slot of new Set([...Object.keys(dm.slots), ...Object.keys(xm.slots)])) {
      const a = dm.slots[slot], b = xm.slots[slot]
      if (text(a) === text(b)) { console.log('  slot', slot, 'same', a.item); continue }
      console.log('  slot', slot, 'DIFFERS')
      console.log('    deluxemenus:', text(a)); console.log('    xalimenu   :', text(b))
    }
  }
  process.exit(0)
})
setTimeout(() => process.exit(0), 60000)
