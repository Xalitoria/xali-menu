import { test } from 'node:test'
import assert from 'node:assert/strict'
import { RangeFile } from '../src/remote.js'
import { Zip } from '../src/zip.js'
import { deflateRawSync } from 'node:zlib'

const data = Uint8Array.from({ length: 1000 }, (_, i) => i % 251)

function source(options = {}) {
  const calls = []
  const file = new RangeFile(data.length, async (start, end) => {
    calls.push([start, end])
    return data.slice(start, Math.min(end, start + (options.cap ?? Infinity)))
  }, options.block ?? 100)
  return { file, calls }
}

test('a slice is made of the blocks it touches, and each block is fetched once', async () => {
  const { file, calls } = source()
  assert.deepEqual(new Uint8Array(await file.slice(150, 260).arrayBuffer()), data.slice(150, 260))
  assert.deepEqual(calls.sort((a, b) => a[0] - b[0]), [[100, 200], [200, 300]])
  await file.slice(160, 170).arrayBuffer()
  assert.equal(calls.length, 2, 'cached')
})

test('the last block is the short one, and a slice past the end is cut', async () => {
  const { file } = source()
  assert.deepEqual(new Uint8Array(await file.slice(950, 5000).arrayBuffer()), data.slice(950))
  assert.equal(file.slice(2000, 3000).size, 0)
})

test('an answer that is cut short is asked for again', async () => {
  const { file, calls } = source({ cap: 30 })
  assert.deepEqual(new Uint8Array(await file.slice(0, 100).arrayBuffer()), data.slice(0, 100))
  assert.deepEqual(calls.map((c) => c[0]), [0, 30, 60, 90])
})

test('a fetch that failed is tried again the next time, and an empty answer is an error', async () => {
  let fail = true
  const file = new RangeFile(10, async (s, e) => { if (fail) throw new Error('offline'); return data.slice(s, e) }, 100)
  await assert.rejects(file.slice(0, 5).arrayBuffer(), /offline/)
  fail = false
  assert.deepEqual(new Uint8Array(await file.slice(0, 5).arrayBuffer()), data.slice(0, 5))
  const empty = new RangeFile(10, async () => new Uint8Array(0), 100)
  await assert.rejects(empty.slice(0, 5).arrayBuffer(), /ended early/)
})

// A zip made by hand with one stored and one deflated entry, to read through a RangeFile.
function makeZip(files) {
  const parts = []
  const central = []
  let offset = 0
  const u16 = (n) => [n & 255, (n >> 8) & 255]
  const u32 = (n) => [n & 255, (n >> 8) & 255, (n >> 16) & 255, (n >>> 24) & 255]
  for (const [name, content, deflate] of files) {
    const raw = Buffer.from(content)
    const body = deflate ? deflateRawSync(raw) : raw
    const method = deflate ? 8 : 0
    const nameBytes = Buffer.from(name)
    const local = Buffer.from([...u32(0x04034b50), ...u16(20), ...u16(0), ...u16(method), ...u16(0), ...u16(0), ...u32(0), ...u32(body.length), ...u32(raw.length), ...u16(nameBytes.length), ...u16(0)])
    parts.push(local, nameBytes, body)
    central.push(Buffer.from([...u32(0x02014b50), ...u16(20), ...u16(20), ...u16(0), ...u16(method), ...u16(0), ...u16(0), ...u32(0), ...u32(body.length), ...u32(raw.length), ...u16(nameBytes.length), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(0), ...u32(offset)]), nameBytes)
    offset += local.length + nameBytes.length + body.length
  }
  const directory = Buffer.concat(central)
  const end = Buffer.from([...u32(0x06054b50), ...u16(0), ...u16(0), ...u16(files.length), ...u16(files.length), ...u32(directory.length), ...u32(offset), ...u16(0)])
  return Buffer.concat([...parts, directory, end])
}

test('a zip is read through a RangeFile, fetching only the parts it needs', async () => {
  const filler = 'x'.repeat(400000)
  const zip = makeZip([['big.bin', filler, false], ['assets/a.json', '{"a":1}', true], ['other.txt', 'hello', false]])
  const ranges = []
  const file = new RangeFile(zip.length, async (start, end) => { ranges.push([start, end]); return new Uint8Array(zip.subarray(start, end)) }, 4096)
  const opened = await Zip.open(file)
  assert.deepEqual(opened.list().sort(), ['assets/a.json', 'big.bin', 'other.txt'])
  assert.deepEqual(await opened.json('assets/a.json'), { a: 1 })
  assert.equal(await opened.text('other.txt'), 'hello')
  const fetched = ranges.reduce((sum, [s, e]) => sum + (e - s), 0)
  assert.ok(fetched < zip.length / 2, `the big entry that was not read stays unread (${fetched} of ${zip.length} bytes)`)
})
