// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh that has FancyNpcs (XM_PLUGINS=FancyNpcs).
const mineflayer = require('mineflayer'); const fs = require('fs')
const SERVER = (process.env.XM_DIR || '/tmp/xm-live') + '/server'
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
const con = c => fs.writeFileSync(SERVER + '/in', c + '\n')
const title = () => bot.currentWindow ? JSON.stringify(bot.currentWindow.title) : 'none'
bot.once('spawn', async () => {
  await wait(3000); con('op Tester'); await wait(800)
  bot.chat('/npc create banker'); await wait(3000)
  const near = Object.values(bot.entities).filter(e => e !== bot.entity && e.position.distanceTo(bot.entity.position) < 4).sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))
  console.log('near', near.map(e => e.type + ':' + (e.username || e.name) + '@' + e.position.distanceTo(bot.entity.position).toFixed(1)).join(','))
  const npc = near[0]
  if (!npc) process.exit(1)
  await bot.activateEntity(npc); await wait(1500); console.log('right click ->', title())
  bot.closeWindow(bot.currentWindow || { id: 0 }); await wait(800); console.log('closed ->', title())
  bot.attack(npc); await wait(1500); console.log('left click ->', title())
  bot.setControlState('sneak', true); await wait(500)
  bot.attack(npc); await wait(1500); console.log('sneaking left click ->', title())
  process.exit(0)
})
setTimeout(() => process.exit(0), 40000)
