import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { action, condition, sentence } from '../src/sentences.js'
import { Schema } from '../src/schema.js'

const schema = new Schema(JSON.parse(readFileSync(new URL('../schema.json', import.meta.url))))

test('an action is said as what it does', () => {
  assert.equal(action({ type: 'close' }), 'Closes the menu')
  assert.equal(action({ type: 'message', text: '&aHello %player_name%' }), 'Tells the player “Hello %player_name%”')
  assert.equal(action({ type: 'give-item', item: { material: 'diamond', amount: 5 } }), 'Gives diamond × 5')
  assert.equal(action({ type: 'delay', ticks: 40, action: { type: 'close' } }), 'After 2 seconds: Closes the menu')
  assert.equal(action({ type: 'delay', ticks: 5, action: { type: 'close' } }), 'After 5 ticks: Closes the menu')
  assert.equal(action({ type: 'chance', percent: 25, action: { type: 'refresh' } }), 'With a 25% chance: Draws the menu again')
  assert.equal(action({ type: 'page', by: 1 }), 'Goes to the next page')
  assert.equal(action({ type: 'page', to: '3' }), 'Goes to page 3')
  assert.equal(action({ type: 'potion', effect: 'minecraft:speed', amplifier: 1, duration: '10s' }), 'Gives speed level 2 for 10s')
  assert.equal(action({ type: 'variable', name: 'n', op: 'INC', value: '2' }), 'Adds 2 to the variable n')
  assert.equal(action({ type: 'all', actions: [{}, {}] }), 'Runs 2 actions')
  assert.equal(action({ type: 'all', actions: [{}] }), 'Runs 1 action')
  assert.equal(action({ type: 'chance', percent: 5 }), 'With a 5% chance: …', 'an action not filled in yet')
})

test('a condition is said as what has to hold', () => {
  assert.equal(condition({ type: 'permission', node: 'shop.use' }), 'The player has the permission shop.use')
  assert.equal(condition({ type: 'compare', left: '%level%', op: 'GTE', right: '5' }), '%level% is at least 5')
  assert.equal(condition({ type: 'level', min: '10' }), "The player's level is at least 10")
  assert.equal(condition({ type: 'health', min: '5', max: '10' }), "The player's health is between 5 and 10")
  assert.equal(condition({ type: 'not', condition: { type: 'never' } }), 'Not: Never holds')
  assert.equal(condition({ type: 'gamemode', modes: ['CREATIVE', 'SPECTATOR'] }), 'The player is in creative, spectator mode')
  assert.ok(action({ type: 'message', text: 'x'.repeat(100) }).includes('…'), 'a long text is cut')
  assert.equal(action({ type: 'addon-thing' }), 'addon-thing', 'a type the page does not know is left as its name')
  assert.deepEqual([action({}), condition(null), sentence('Catalog', { type: 'players' })], ['', '', ''])
  assert.deepEqual([sentence('Action', { type: 'close' }), sentence('Condition', { type: 'never' })], ['Closes the menu', 'Never holds'])
})

test('every action and condition of the schema has a sentence of its own', () => {
  for (const [base, say] of [['Action', action], ['Condition', condition]]) {
    for (const type of schema.variants(base)) {
      // A blank one, as the form makes it: the sentence is not just the name (the default branch), and says no "undefined".
      const blank = schema.blankVariant(base, type)
      const said = say(blank)
      assert.ok(said && said !== type, `${base} ${type} has no sentence`)
      assert.ok(!/undefined|NaN|\[object/.test(said), `${base} ${type}: ${said}`)
    }
  }
})

test('a sentence to edit an action or condition through only holds keys the type has, in controls the form can make', async () => {
  const { TEMPLATES, tokens, keysOf, templateOf } = await import('../src/inline.js')
  assert.deepEqual(tokens('{take|Gives|Takes} {amount} money'), [{ key: 'take', off: 'Gives', on: 'Takes' }, { text: ' ' }, { key: 'amount', off: undefined, on: undefined }, { text: ' money' }])
  assert.deepEqual(keysOf(templateOf('Action', 'message')), ['text'])
  assert.equal(templateOf('Action', 'close'), null)
  for (const [base, types] of Object.entries(TEMPLATES)) {
    for (const [type, template] of Object.entries(types)) {
      const fields = schema.fields(schema.variantType(base, type))
      for (const token of tokens(template).filter((t) => t.key)) {
        const field = fields.find((f) => f.key === token.key)
        assert.ok(field, `${base} ${type} has no key ${token.key}`)
        assert.ok(['string', 'int', 'number', 'enum', 'duration', 'dynamic', 'boolean'].includes(field.type.kind), `${base} ${type}.${token.key} is a ${field.type.kind}`)
        if (token.on !== undefined) assert.equal(field.type.kind, 'boolean', `${type}.${token.key} is told as two words and must be a switch`)
      }
    }
  }
})
