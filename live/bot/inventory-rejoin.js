// Part of the live tests of XaliMenu, see ../README.md. Needs ProtocolLib or PacketEvents in the server's plugins (the menu `p`, `/xp`, draws in the player's own inventory).
// Gives Tester a known inventory, opens /xp, clicks a drawn slot and picks up and shift-clicks real items, closes, and compares the inventory with the one before: as the
// client sees it, and after leaving and joining again, which is what the server really holds. The menu's items only ever exist in packets, so nothing may be lost or added.
const mineflayer = require('mineflayer')
const { execSync } = require('child_process')
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = (c) => execSync(`${require('path').join(__dirname, '..', 'server.sh')} cmd "${c}"`, { stdio: 'ignore' })
const make = () => mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const snap = (b) => b.inventory.slots.map((s, i) => s ? `${i}:${s.type}x${s.count}` : null).filter(Boolean).join(' ')
const total = (s) => s.split(' ').filter(Boolean).reduce((a, t) => a + Number(t.split('x')[1]), 0)
async function main() {
  let bot = make()
  await new Promise((r) => bot.once('spawn', r)); await wait(3000)
  con('op Tester'); await wait(800); con('clear Tester'); await wait(500)
  for (const g of ['diamond 5', 'dirt 1', 'sand 1', 'gravel 1', 'apple 7', 'poppy 1', 'dandelion 1', 'allium 1', 'azure_bluet 1', 'emerald 2', 'cobblestone 9']) { con('give Tester ' + g); await wait(150) }
  await wait(1200)
  const before = snap(bot); console.log('BEFORE   ' + before)
  bot.chat('/xp'); await wait(1500)
  await bot.clickWindow(40, 0, 0); await wait(500)
  await bot.clickWindow(36, 0, 0); await wait(300)
  await bot.clickWindow(36, 0, 1); await wait(300)
  await bot.clickWindow(37, 0, 0); await wait(300)
  bot.closeWindow(bot.currentWindow)
  for (const ms of [300, 1000, 2000]) { await wait(ms); console.log(`CLIENT +${ms}ms  ${snap(bot)}`) }
  // The server saves a player when they leave; joining again at once can read what was saved before.
  bot.quit(); await wait(8000)
  bot = make(); await new Promise((r) => bot.once('spawn', r))
  // The inventory comes in pieces some moments after the spawn: wait until it has been the same for two seconds (up to 20), so that a slow packet is not taken for a loss.
  let last = ''; let same = 0
  for (let i = 0; i < 40 && same < 4; i++) { await wait(500); const now = snap(bot); same = now === last && now !== '' ? same + 1 : 0; last = now }
  const after = snap(bot); console.log('REJOINED ' + after)
  console.log(`items before=${total(before)} rejoined=${total(after)} ${before === after ? 'SAME' : 'DIFFERENT'}`)
  process.exit(0)
}
main()
setTimeout(() => process.exit(2), 90000)
