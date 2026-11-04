import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pickModel, modelTexture, split } from '../src/models.js'

test('a plain definition picks its model', () => {
  assert.equal(pickModel({ model: { type: 'minecraft:model', model: 'minecraft:item/stick' } }), 'minecraft:item/stick')
})

test('range dispatch on custom model data picks the highest threshold that is reached', () => {
  const definition = {
    model: {
      type: 'minecraft:range_dispatch', property: 'minecraft:custom_model_data',
      entries: [{ threshold: 1, model: { type: 'minecraft:model', model: 'a:one' } }, { threshold: 5, model: { type: 'minecraft:model', model: 'a:five' } }],
      fallback: { type: 'minecraft:model', model: 'minecraft:item/paper' },
    },
  }
  assert.equal(pickModel(definition, { customModelData: [7] }), 'a:five')
  assert.equal(pickModel(definition, { customModelData: [3] }), 'a:one')
  assert.equal(pickModel(definition, { customModelData: [0] }), 'minecraft:item/paper')
  assert.equal(pickModel(definition), 'minecraft:item/paper')
})

test('select and condition fall back, composite takes the first model', () => {
  const leaf = (m) => ({ type: 'minecraft:model', model: m })
  assert.equal(pickModel({ model: { type: 'minecraft:select', property: 'x', cases: [], fallback: leaf('a:b') } }), 'a:b')
  assert.equal(pickModel({ model: { type: 'minecraft:condition', property: 'x', on_true: leaf('t'), on_false: leaf('f') } }), 'f')
  assert.equal(pickModel({ model: { type: 'minecraft:composite', models: [leaf('first'), leaf('second')] } }), 'first')
})

test('a model names its texture directly, through a reference, or through its parent', async () => {
  const models = {
    'a:direct': { textures: { layer0: 'a:item/x' } },
    'a:ref': { textures: { all: '#side', side: 'a:block/y' } },
    'a:child': { parent: 'a:direct' },
  }
  const read = async (ns, path) => models[`${ns}:${path}`] ?? null
  assert.equal(await modelTexture(read, 'a:direct'), 'a:item/x')
  assert.equal(await modelTexture(read, 'a:ref'), 'a:block/y')
  assert.equal(await modelTexture(read, 'a:child'), 'a:item/x')
  assert.equal(await modelTexture(read, 'a:missing'), null)
  assert.deepEqual(split('stone'), { ns: 'minecraft', path: 'stone' })
})
