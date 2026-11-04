import { test } from 'node:test'
import assert from 'node:assert/strict'
import { skin, Texture } from '../src/assets.js'

const sheet = (width, height, fill) => {
  const data = new Uint8ClampedArray(width * height * 4)
  for (let i = 0; i < width * height; i++) data.set(fill, i * 4)
  return new Texture(width, height, data)
}
const alpha = (t, x, y) => t.data[(y * t.width + x) * 4 + 3]

test('an old skin is put on a 64 by 64 sheet and its solid hat layer is dropped', () => {
  const made = skin(sheet(64, 32, [10, 20, 30, 255]))
  assert.deepEqual([made.width, made.height], [64, 64])
  assert.equal(alpha(made, 40, 8), 0, 'a hat with nothing transparent shows nothing')
  assert.equal(alpha(made, 8, 8), 255, 'the head stays')
})

test('a hat with transparent pixels is kept, and the base head is made opaque', () => {
  const t = sheet(64, 64, [1, 2, 3, 0])
  t.data[(8 * 64 + 40) * 4 + 3] = 255 // a pixel of the hat
  const made = skin(t)
  assert.equal(alpha(made, 40, 8), 255)
  assert.equal(alpha(made, 5, 5), 255, 'the base layer of the head is never transparent')
  assert.equal(alpha(made, 50, 5), 0)
})
