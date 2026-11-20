// Part of the live tests of XaliMenu, see ../README.md. Needs a server started with ../server.sh, set up with XM_EDITOR=1.
// Joins as Tester, asks for an editor link with /xmenu edit and prints the chat (the link's token is after `token=`), then stays
// online until its stdin closes, so that the endpoint can be tried with curl for a player who is on the server.
const mineflayer = require('mineflayer')
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.XM_PORT || 25599), username: 'Tester', version: '26.1', auth: 'offline', hideErrors: true })
const wait = ms => new Promise(r => setTimeout(r, ms))
bot.on('message', (m) => console.log('CHAT ' + m.toString()))
bot.once('spawn', async () => {
  await wait(5000)
  bot.chat('/xmenu edit')
  await wait(1500)
  console.log('READY')
})
process.stdin.on('end', () => process.exit(0))
setTimeout(() => process.exit(0), 300000)
