import { test } from 'node:test'
import assert from 'node:assert/strict'
import { slotOf, equipmentOf, armorLayers, trimPath, compose, FIGURE } from '../src/armor.js'
import { Texture } from '../src/assets.js'

test('the slot, the equipment, the layers and the trim texture of armor are named as the game names them', () => {
  assert.deepEqual(['iron_chestplate', 'minecraft:golden_boots', 'leather_helmet', 'turtle_helmet', 'netherite_leggings', 'stone', 'elytra'].map(slotOf), ['chest', 'feet', 'head', 'head', 'legs', null, null])
  assert.deepEqual(['iron_chestplate', 'golden_boots', 'turtle_helmet', 'chainmail_leggings'].map(equipmentOf), ['iron', 'gold', 'turtle_scute', 'chainmail'])
  // The layers come from the equipment asset; leather is dyed (and undyed is the game's brown), its overlay is not.
  const leather = { layers: { humanoid: [{ texture: 'minecraft:leather', dyeable: { color_when_undyed: -6265536 } }, { texture: 'minecraft:leather_overlay' }] } }
  assert.deepEqual(armorLayers('head', 'leather_helmet', leather, undefined), [{ path: 'entity/equipment/humanoid/leather', tint: 0xa06540 }, { path: 'entity/equipment/humanoid/leather_overlay', tint: null }])
  assert.equal(armorLayers('head', 'leather_helmet', leather, 0xff0000)[0].tint, 0xff0000)
  assert.deepEqual(armorLayers('legs', 'iron_leggings', null, undefined), [{ path: 'entity/equipment/humanoid_leggings/iron', tint: null }], 'without the asset, the texture of the same name')
  assert.equal(trimPath('chest', 'iron_chestplate', { pattern: 'minecraft:coast', material: 'gold' }), 'trims/entity/humanoid/coast_gold')
  assert.equal(trimPath('legs', 'iron_leggings', { pattern: 'coast', material: 'gold' }), 'trims/entity/humanoid_leggings/coast_gold')
  assert.equal(trimPath('chest', 'golden_chestplate', { pattern: 'coast', material: 'gold' }), 'trims/entity/humanoid/coast_gold_darker', 'armor of the material itself gets the darker shade')
  assert.equal(trimPath('chest', 'iron_chestplate', { pattern: 'coast' }), null)
})

test('the armor is drawn on the front of the figure, the left side turned over, and the trim over the armor', () => {
  // A 64 by 32 armor texture that is red where the front of the head is and blue everywhere else; a trim that is green on one pixel of the head.
  const armor = new Texture(64, 32, new Uint8ClampedArray(64 * 32 * 4))
  for (let y = 0; y < 32; y++) for (let x = 0; x < 64; x++) armor.data.set(x >= 8 && x < 16 && y >= 8 && y < 16 ? [255, 0, 0, 255] : [0, 0, 255, 255], (y * 64 + x) * 4)
  const trim = new Texture(64, 32, new Uint8ClampedArray(64 * 32 * 4))
  trim.data.set([0, 255, 0, 255], (8 * 64 + 8) * 4)
  const textures = (path) => ({ a: armor, t: trim })[path]
  const head = compose('head', [{ path: 'a', tint: null }], 't', textures, 2)
  assert.deepEqual([head.width, head.height], [FIGURE.width * 2, FIGURE.height * 2])
  const at = (x, y) => [...head.data.slice((y * head.width + x) * 4, (y * head.width + x) * 4 + 4)]
  assert.deepEqual(at(10, 1), [255, 0, 0, 255], 'the head is where the figure has it')
  assert.deepEqual(at(8, 0), [0, 255, 0, 255], 'the trim is over the armor')
  assert.equal(at(0, 0)[3], 0, 'nothing is drawn outside the piece')
  // The tint multiplies the texture (leather); a missing texture is left out.
  const dyed = compose('head', [{ path: 'a', tint: 0x00ff00 }], null, textures, 1)
  assert.deepEqual([...dyed.data.slice((1 * dyed.width + 5) * 4, (1 * dyed.width + 5) * 4 + 4)], [0, 0, 0, 255])
  assert.equal(compose('head', [{ path: 'none', tint: null }], null, textures, 1).data.every((v) => v === 0), true)
  // The chestplate has two arms, and the left one is the right one turned over.
  const chest = compose('chest', [{ path: 'a', tint: null }], null, () => {
    const t = new Texture(64, 32, new Uint8ClampedArray(64 * 32 * 4))
    for (let y = 20; y < 32; y++) t.data.set([255, 255, 255, 255], (y * 64 + 44) * 4)
    return t
  }, 1)
  const px = (x, y) => chest.data[(y * chest.width + x) * 4 + 3]
  assert.deepEqual([px(0, 10), px(3, 10), px(12, 10), px(15, 10)], [255, 0, 0, 255], 'the first column of the right arm is at the outside of one and the inside of the other')
})
