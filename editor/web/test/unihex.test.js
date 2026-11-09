import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseHex, measure, pixels, readOverrides, UnihexSet } from '../src/unihex.js'

// A narrow glyph (8 bits) whose bits are set in columns 1 to 3 of every row, and a wide one (16 bits) with one pixel at column 15.
const narrow = '70'.repeat(16)
const wide = '0001'.repeat(16)

test('hex lines are read by code point, and bad lines are skipped', () => {
  const glyphs = parseHex(`0041:${narrow}\n\nnot a line\n00E9:${wide}\n0042:abc\n`)
  assert.deepEqual([...glyphs.keys()], [0x41, 0xe9])
  assert.equal(glyphs.get(0x41), narrow)
})

test('without an override the columns that have something are kept, and the advance is half the width plus one', () => {
  const size = measure(narrow, 0x41)
  assert.deepEqual(size, { left: 1, right: 3, advance: 2 })
  assert.deepEqual(measure(wide, 0x4e00), { left: 15, right: 15, advance: 1 })
  assert.deepEqual(measure('00'.repeat(16), 0x20), { left: 0, right: 7, advance: 5 }, 'a blank glyph keeps its whole width')
})

test('an override says the columns by range of characters', () => {
  const overrides = readOverrides([{ from: '㈀', to: '鿿', left: 0, right: 15 }])
  assert.equal(overrides[0].from, 0x3200)
  assert.deepEqual(measure(wide, 0x4e00, overrides), { left: 0, right: 15, advance: 9 })
  assert.deepEqual(measure(wide, 0x41, overrides), { left: 15, right: 15, advance: 1 }, 'outside the range it is measured')
})

test('pixels come out white where a bit is set, leftmost bit first', () => {
  const { width, height, data } = pixels(narrow, 0, 7)
  assert.equal(width, 8)
  assert.equal(height, 16)
  const on = (x, y) => data[(y * width + x) * 4 + 3] === 255
  assert.deepEqual([0, 1, 2, 3, 4].map((x) => on(x, 5)), [false, true, true, true, false])
  const cut = pixels(narrow, 1, 3)
  assert.equal(cut.width, 3)
  assert.ok([0, 1, 2].every((x) => cut.data[(4 * 3 + x) * 4 + 3] === 255))
})

test('the fallback font is read once, when asked, and earlier providers win', async () => {
  let loads = 0
  // The zip is made by hand: one stored entry.
  const text = `0041:${narrow}\n00E9:${wide}\n`
  const set = new UnihexSet([{ hex_file: 'minecraft:font/a.zip' }], async () => { loads++; return storedZip('a.hex', text) }, (picture, advance) => ({ picture, advance }))
  assert.equal(set.get('A'), null, 'nothing is known before it is loaded')
  await set.load()
  await set.load()
  assert.equal(loads, 1)
  assert.equal(set.get('A').advance, 2)
  assert.equal(set.get('é').picture.width, 1)
  assert.equal(set.get('z'), null)
})

/** A zip with one stored entry, enough for the reader. */
function storedZip(name, text) {
  const data = Buffer.from(text)
  const n = Buffer.from(name)
  const crc = crc32(data)
  const local = Buffer.alloc(30)
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt32LE(crc, 14)
  local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(n.length, 26)
  const central = Buffer.alloc(46)
  central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt32LE(crc, 16)
  central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(n.length, 28)
  const offset = local.length + n.length + data.length
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10)
  end.writeUInt32LE(central.length + n.length, 12); end.writeUInt32LE(offset, 16)
  return new Uint8Array(Buffer.concat([local, n, data, central, n, end]))
}

function crc32(buffer) {
  let c, crc = 0xffffffff
  for (const byte of buffer) {
    c = (crc ^ byte) & 0xff
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    crc = (crc >>> 8) ^ c
  }
  return (crc ^ 0xffffffff) >>> 0
}

test('JSON with a comma before a closing bracket is read, other errors stay', async () => {
  const { lenient } = await import('../src/assets.js')
  assert.deepEqual(lenient('{"a": [1, 2,], "b": {"c": 1,},}'), { a: [1, 2], b: { c: 1 } })
  assert.throws(() => lenient('{"a": }'))
})
