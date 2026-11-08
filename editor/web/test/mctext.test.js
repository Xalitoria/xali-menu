import { test } from 'node:test'
import assert from 'node:assert/strict'
import { runs, plain, colorOf, mix } from '../src/mctext.js'

const colors = (text) => runs(text).map((r) => [r.text, r.color ?? null])

test('colors are read by hex, by the names of the color module and by the legacy names', () => {
  assert.equal(colorOf('#F80'), '#ff8800')
  assert.equal(colorOf('00ff88'), '#00ff88')
  assert.equal(colorOf('navy_blue'), '#000080')
  assert.equal(colorOf('NavyBlue'), '#000080')
  assert.equal(colorOf('blue'), '#0000ff')
  assert.equal(colorOf('nonsense'), null)
})

test('legacy and hex codes color the text that follows', () => {
  assert.deepEqual(colors('&cred &#00ff88green &#f00short &x&1&2&3&4&5&6bukkit'),
    [['red ', '#ff5555'], ['green ', '#00ff88'], ['short ', '#ff0000'], ['bukkit', '#123456']])
  assert.deepEqual(colors('§6gold'), [['gold', '#ffaa00']])
})

test('a color ends the decorations before it, and a tag does not', () => {
  const [bold, red] = runs('&lbold&cred')
  assert.equal(bold.bold, true)
  assert.equal(red.bold, undefined)
  const [one, two] = runs('<bold>a<red>b')
  assert.equal(two.bold, true)
  assert.equal(two.color, '#ff5555')
  assert.equal(one.color, undefined)
})

test('template colors: {#name} and {#rrggbb}', () => {
  assert.deepEqual(colors('{#navyblue}a{#f80}b'), [['a', '#000080'], ['b', '#ff8800']])
  assert.deepEqual(colors('{#notacolor}a'), [['{#notacolor}a', null]])
})

test('the gradient of the color module: one color for each character, the ends as asked', () => {
  const got = colors('{#000>}rank{#fff<}')
  assert.equal(plain('{#000>}rank{#fff<}'), 'rank')
  assert.deepEqual(got.map(([t]) => t), ['r', 'a', 'n', 'k'])
  assert.equal(got[0][1], '#000000')
  assert.equal(got[3][1], '#ffffff')
  assert.equal(got[1][1], '#555255', 'mixed with Mixbox, as the plugin does')
  assert.deepEqual(colors('{#000>}x{#fff<}'), [['x', '#000000']], 'a single character takes the start color')
})

test('a gradient keeps its decorations, skips tags and counts an escaped < as one character', () => {
  const got = runs('{#000>}&lab{#fff<}')
  assert.deepEqual(got.map((r) => [r.text, r.bold]), [['a', true], ['b', true]])
  assert.equal(plain('{#000>}a<red>b{#fff<}'), 'ab')
  assert.equal(plain('{#000>}a\\<b{#fff<}'), 'a<b')
})

test('a separator splits one gradient into two that meet in its color', () => {
  const got = colors('{#f00>}ab{#00f<>}cd{#0f0<}')
  assert.deepEqual(got.map(([t]) => t), ['a', 'b', 'c', 'd'])
  assert.equal(got[0][1], '#ff0000')
  assert.equal(got[1][1], '#0000ff')
  assert.equal(got[2][1], '#0000ff')
  assert.equal(got[3][1], '#00ff00')
})

test('MiniMessage gradients, rainbows and colors', () => {
  const g = colors('<gradient:#000000:#ffffff>abc</gradient>d')
  assert.deepEqual(g.map(([t]) => t), ['a', 'b', 'c', 'd'])
  assert.deepEqual([g[0][1], g[2][1], g[3][1]], ['#000000', '#ffffff', null])
  const three = colors('<gradient:red:#00ff00:blue>abc')
  assert.deepEqual([three[0][1], three[1][1], three[2][1]], ['#ff5555', '#00ff00', '#5555ff'])
  const rainbow = colors('<rainbow>abcd')
  assert.equal(rainbow.length, 4)
  assert.equal(rainbow[0][1], '#ff0000')
  assert.deepEqual(colors('<color:#123456>a<c:gold>b<#ffffff>c'), [['a', '#123456'], ['b', '#ffaa00'], ['c', '#ffffff']])
})

test('mixing runs from the first color to the last', () => {
  assert.equal(mix(['#000000', '#ffffff'], 0), '#000000')
  assert.equal(mix(['#000000', '#ffffff'], 1), '#ffffff')
  assert.equal(mix(['#ff0000', '#00ff00', '#0000ff'], 0.5), '#00ff00')
})

test('text with no format is one run, and a lone & or < is kept', () => {
  assert.deepEqual(colors('a & b < c'), [['a & b < c', null]])
})
// The tests of the legacy codes and tags, from before the color module's formats were added.
test('legacy codes set the color and styles, and &r clears them', () => {
  assert.deepEqual(runs('&6Gold &lBold&r plain'), [
    { text: 'Gold ', color: '#ffaa00' },
    { text: 'Bold', color: '#ffaa00', bold: true },
    { text: ' plain' },
  ])
})

test('a color code ends the styles before it, as in the game', () => {
  assert.deepEqual(runs('&lA&cB'), [{ text: 'A', bold: true }, { text: 'B', color: '#ff5555' }])
})

test('hex colors and minimessage tags work', () => {
  assert.equal(runs('&#ff8800X')[0].color, '#ff8800')
  assert.deepEqual(runs('<red>R</red>N'), [{ text: 'R', color: '#ff5555' }, { text: 'N', color: undefined }])
  assert.equal(plain('<bold>b</bold> &aok'), 'b ok')
})

test('text that is not a code stays as typed', () => {
  assert.equal(plain('a < b & c'), 'a < b & c')
})

test('a font tag puts its key on the runs inside it', () => {
  assert.deepEqual(runs('a<font:nexo:default>b</font>c'), [{ text: 'a' }, { text: 'b', font: 'nexo:default' }, { text: 'c', font: undefined }])
})
