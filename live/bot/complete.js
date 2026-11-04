// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
bot.once('spawn', async () => {
  await wait(3000)
  for (const line of ['/xa ', '/xa t', '/xa everyone ', '/xa everyone s', '/xa everyone fast ', '/xa everyone fast spam g', '/xa everyone fast x ', '/xa everyone fast x y ']) {
    const found = await bot.tabComplete(line, false, false, 3000).catch(e => 'error ' + e.message)
    console.log(JSON.stringify(line), '->', JSON.stringify(Array.isArray(found) ? found.map(m => m.match ?? m) : found))
  }
  process.exit(0)
})
setTimeout(() => process.exit(0), 30000)
