import { test } from 'node:test'
import assert from 'node:assert/strict'
import { skin, Texture } from '../src/assets.js'
import { skinSpec } from '../src/itemrender.js'

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

test('skinSpec resolves player names, texture URLs, base64, head: material, and profile NBT', () => {
  const url = 'https://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680'
  const b64 = Buffer.from(JSON.stringify({ textures: { SKIN: { url } } })).toString('base64')
  assert.equal(skinSpec({ material: 'player_head', skull: 'audizian' }), 'player:audizian')
  assert.equal(skinSpec({ material: 'player_head', skull: url }), `url:${url}`)
  assert.equal(skinSpec({ material: 'player_head', skull: b64 }), `url:${url}`)
  assert.equal(skinSpec({ material: 'player_head', skull: `texture:${b64}` }), `url:${url}`)
  assert.equal(skinSpec({ material: 'player_head', skull: '%player_name%' }), null)
  assert.equal(skinSpec({ material: 'head:Notch' }), 'player:Notch')
  assert.equal(skinSpec({ material: `texture:${b64}` }), `url:${url}`)
  assert.equal(skinSpec({ material: 'player_head', nbt: '[minecraft:profile={name:"audizian"}]' }), 'player:audizian')
  assert.equal(skinSpec({ material: 'stone', nbt: '[minecraft:profile={name:"audizian"}]' }), null)
})
