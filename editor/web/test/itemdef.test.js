import { test } from 'node:test'
import assert from 'node:assert/strict'
import { chooseModels, tintColor } from '../src/itemdef.js'
import { Texture, recolor } from '../src/assets.js'

const leaf = (model, tints) => ({ type: 'minecraft:model', model, tints })

test('a plain definition has one model', () => {
  assert.deepEqual(chooseModels({ model: leaf('minecraft:block/stone') }, {}), [{ model: 'minecraft:block/stone', tints: [] }])
})

test('tints take the dye and the potion of the item, or their default', () => {
  const definition = { model: leaf('minecraft:item/leather_helmet', [{ type: 'minecraft:dye', default: -6265536 }, { type: 'minecraft:constant', value: -1 }]) }
  assert.deepEqual(chooseModels(definition, {})[0].tints, [0xa06540, 0xffffff])
  assert.deepEqual(chooseModels(definition, { dyedColor: 0x112233 })[0].tints, [0x112233, 0xffffff])
  assert.equal(tintColor({ type: 'minecraft:potion', default: -13083194 }, { potionColor: 0xff0000 }), 0xff0000)
  assert.equal(tintColor({ type: 'minecraft:constant', value: [1, 0.5, 0] }, {}), 0xff8000)
})

test('custom model data picks by threshold, by string and by flag', () => {
  const definition = {
    model: {
      type: 'minecraft:range_dispatch', property: 'minecraft:custom_model_data',
      entries: [{ threshold: 1, model: leaf('a:one') }, { threshold: 5, model: leaf('a:five') }],
      fallback: {
        type: 'minecraft:select', property: 'minecraft:custom_model_data',
        cases: [{ when: 'gold', model: leaf('a:gold') }], fallback: {
          type: 'minecraft:condition', property: 'minecraft:custom_model_data', on_true: leaf('a:flag'), on_false: leaf('a:plain'),
        },
      },
    },
  }
  const model = (state) => chooseModels(definition, state)[0].model
  assert.equal(model({ customModelData: { floats: [7] } }), 'a:five')
  assert.equal(model({ customModelData: { floats: [3] } }), 'a:one')
  assert.equal(model({ customModelData: { strings: ['gold'] } }), 'a:gold')
  assert.equal(model({ customModelData: { flags: [true] } }), 'a:flag')
  assert.equal(model({}), 'a:plain')
})

test('select on the display context takes the gui case', () => {
  const definition = {
    model: {
      type: 'minecraft:select', property: 'minecraft:display_context',
      cases: [{ when: ['gui', 'fixed'], model: leaf('a:flat') }], fallback: leaf('a:held'),
    },
  }
  assert.equal(chooseModels(definition, {})[0].model, 'a:flat')
})

test('wear picks the damaged model, and the composite draws all its models', () => {
  const wear = {
    model: { type: 'minecraft:condition', property: 'minecraft:damaged', on_true: leaf('a:worn'), on_false: leaf('a:new') },
  }
  assert.equal(chooseModels(wear, { damage: 3, maxDamage: 10 })[0].model, 'a:worn')
  assert.equal(chooseModels(wear, { damage: 0, maxDamage: 10 })[0].model, 'a:new')
  assert.equal(chooseModels(wear, { damage: 3, maxDamage: 0 })[0].model, 'a:new')
  const composite = { model: { type: 'minecraft:composite', models: [leaf('a:one'), leaf('a:two')] } }
  assert.deepEqual(chooseModels(composite, {}).map((e) => e.model), ['a:one', 'a:two'])
})

test('special models and things that need a player are kept or skipped', () => {
  const special = { model: { type: 'minecraft:special', base: 'minecraft:item/chest', model: { type: 'minecraft:chest', texture: 'minecraft:normal' } } }
  const [entry] = chooseModels(special, {})
  assert.equal(entry.base, 'minecraft:item/chest')
  assert.equal(entry.special.type, 'minecraft:chest')
  const bow = { model: { type: 'minecraft:condition', property: 'minecraft:using_item', on_true: leaf('a:drawn'), on_false: leaf('a:rest') } }
  assert.equal(chooseModels(bow, {})[0].model, 'a:rest')
})

import { colormap } from '../src/itemdef.js'

test('a colormap is read by temperature across and downfall times temperature down', () => {
  const data = new Uint8ClampedArray(256 * 256 * 4)
  for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) data.set([x, y, 7, 255], (y * 256 + x) * 4)
  const texture = { width: 256, height: 256, data }
  // temperature 0.5, downfall 1.0: x = 127, y = floor((1 - 0.5) * 255) = 127
  assert.equal(colormap(texture, 0.5, 1), (127 << 16) | (127 << 8) | 7)
  assert.equal(colormap(texture, 1, 1), (0 << 16) | (0 << 8) | 7)
  assert.equal(colormap(null, 1, 1), null)
})

test('a trimmed armor picks its trim model by the material, and a texture the game builds from a palette is made from the grayscale one', async () => {
  const definition = { model: { type: 'minecraft:select', property: 'minecraft:trim_material', cases: [{ when: 'minecraft:gold', model: { type: 'minecraft:model', model: 'minecraft:item/iron_chestplate_gold_trim' } }], fallback: { type: 'minecraft:model', model: 'minecraft:item/iron_chestplate' } } }
  assert.equal(chooseModels(definition, { trimMaterial: 'gold' })[0].model, 'minecraft:item/iron_chestplate_gold_trim')
  assert.equal(chooseModels(definition, { trimMaterial: 'ruby' })[0].model, 'minecraft:item/iron_chestplate')
  assert.equal(chooseModels(definition, {})[0].model, 'minecraft:item/iron_chestplate')
  // Two key colors, white and black, become two colors of the palette; a pixel that is not a key stays, and transparency is kept.
  const key = new Texture(2, 1, new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 255]))
  const gold = new Texture(2, 1, new Uint8ClampedArray([250, 238, 77, 255, 10, 20, 30, 255]))
  const grey = new Texture(3, 1, new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 128, 7, 7, 7, 255]))
  assert.deepEqual([...recolor(grey, key, gold).data], [250, 238, 77, 255, 10, 20, 30, 128, 7, 7, 7, 255])
})
