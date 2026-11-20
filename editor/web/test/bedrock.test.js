import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { Schema } from '../src/schema.js'
import { check } from '../src/check.js'
import {
  DEVICES, deviceById, matchesBedrockFilter, presentationFor, stripPrivateUse,
  titleForDevice, clickChoices, isFiller, simpleFormButtons, compatibilityNotes,
} from '../src/bedrock.js'

const schema = new Schema(JSON.parse(readFileSync(new URL('../schema.json', import.meta.url))))
const javaPc = deviceById('java')
const bedrockPc = deviceById('bedrock-pc')
const bedrockMobile = deviceById('bedrock-mobile')
const bedrockConsole = deviceById('bedrock-console')

test('devices cover Java PC and Bedrock PC, Mobile and Console', () => {
  assert.equal(DEVICES.length, 4)
  assert.equal(javaPc.edition, 'JAVA')
  assert.equal(bedrockPc.ui, 'CLASSIC')
  assert.equal(bedrockMobile.input, 'TOUCH')
  assert.equal(bedrockMobile.ui, 'POCKET')
  assert.equal(bedrockConsole.input, 'CONTROLLER')
})

test('presentationFor mirrors device filters, explicit modes and auto-inference', () => {
  const plain = { title: 'Shop', size: 27, items: {} }
  assert.deepEqual(presentationFor(plain, javaPc, 'always-form'), { kind: 'inventory' })
  assert.deepEqual(presentationFor(plain, bedrockPc, 'touch-form'), { kind: 'inventory' })
  assert.deepEqual(presentationFor(plain, bedrockMobile, 'touch-form'), { kind: 'simple' })
  assert.deepEqual(presentationFor(plain, bedrockConsole, 'touch-form'), { kind: 'simple' })
  assert.deepEqual(presentationFor(plain, bedrockPc, 'always-form'), { kind: 'simple' })

  const touchScoped = { title: 'Shop', bedrock: { mode: 'SIMPLE', inputs: ['TOUCH'] } }
  assert.equal(matchesBedrockFilter(bedrockMobile, touchScoped.bedrock), true)
  assert.equal(matchesBedrockFilter(bedrockPc, touchScoped.bedrock), false)
  assert.deepEqual(presentationFor(touchScoped, bedrockMobile, 'inventory'), { kind: 'simple' })
  assert.deepEqual(presentationFor(touchScoped, bedrockPc, 'always-form'), { kind: 'inventory' })

  const redirect = { title: 'Shop', bedrock: { devices: ['MOBILE'], menu: 'shop-mobile' } }
  assert.deepEqual(presentationFor(redirect, bedrockMobile), { kind: 'redirect', menu: 'shop-mobile' })
  assert.deepEqual(presentationFor(redirect, bedrockPc), { kind: 'inventory' })

  assert.deepEqual(presentationFor({ title: 'T', bedrock: { fields: [{ type: 'input', id: 'a', text: 'A' }] } }, bedrockPc), { kind: 'custom' })
  assert.deepEqual(presentationFor({ title: 'T', bedrock: { confirm: { text: 'Yes' } } }, bedrockPc), { kind: 'modal' })
  assert.deepEqual(presentationFor({ title: 'T', bedrock: { buttons: [{ text: 'Go' }] } }, bedrockPc), { kind: 'simple' })
})

test('simpleFormButtons skips fillers, deduplicates multi-slot items and keeps multi-click choices', () => {
  const menu = {
    title: '\uE001&8Shop',
    size: 27,
    items: {
      border: { material: 'gray_stained_glass_pane', name: ' ', slots: [0, 1, 2] },
      hidden: { material: 'stone', name: 'Secret', slot: 3, bedrock: { hidden: true } },
      wide: {
        material: 'diamond',
        name: '&bDiamond',
        lore: ['&7Price: 100'],
        slots: [11, 12, 13],
        bedrock: { 'left-label': '&aBuy', 'right-label': '&cSell' },
        click: { left: { actions: [{ type: 'close' }] }, right: { actions: [{ type: 'close' }] } },
      },
    },
  }
  assert.equal(stripPrivateUse(menu.title), '&8Shop')
  assert.equal(titleForDevice(menu, bedrockMobile), '&8Shop')
  assert.equal(isFiller(menu.items.border), true)
  assert.equal(isFiller(menu.items.hidden), true)
  assert.equal(isFiller(menu.items.wide), false)
  assert.deepEqual(clickChoices(menu.items.wide).map((c) => c.label), ['&aBuy', '&cSell'])

  const result = simpleFormButtons(menu)
  assert.equal(result.explicit, false)
  assert.equal(result.buttons.length, 1, 'border and hidden skipped, multi-slot item deduplicated')
  assert.equal(result.buttons[0].id, 'wide')
  assert.equal(result.buttons[0].label, '&bDiamond')
  assert.equal(result.buttons[0].sub, '&7Price: 100')
  assert.equal(result.buttons[0].choices.length, 2)
})

test('compatibilityNotes flags Java glyphs, Pocket inventory overlays, accept slots and multi-click items', () => {
  const menu = {
    title: '<shift:-8><glyph:shop_bg>&8Shop',
    size: 27,
    bedrock: { mode: 'SIMPLE' },
    items: {
      hotbar: { material: 'compass', 'inventory-slots': [0] },
      deposit: { material: 'air', slot: 4, accept: { then: [{ type: 'close' }] } },
      trade: {
        material: 'emerald',
        slot: 13,
        click: { left: { actions: [{ type: 'close' }] }, right: { actions: [{ type: 'close' }] } },
      },
    },
  }
  assert.equal(compatibilityNotes(menu, javaPc).length, 0)
  const notes = compatibilityNotes(menu, bedrockMobile)
  assert.ok(notes.some((n) => n.level === 'warn' && n.message.includes('bedrock.title')))
  assert.ok(notes.some((n) => n.level === 'info' && n.message.includes('inventory-slots')))
  assert.ok(notes.some((n) => n.level === 'warn' && n.message.includes('deposited items')))
  assert.ok(notes.some((n) => n.level === 'info' && n.message.includes('multi-click')))
})

test('check validates Bedrock modal buttons, custom fields, duplicate ids and bounds', () => {
  const modalProblems = check(schema, { title: 'Confirm', bedrock: { mode: 'MODAL' } })
  assert.ok(modalProblems.some((p) => p.path.join('.') === 'bedrock.confirm'))
  assert.ok(modalProblems.some((p) => p.path.join('.') === 'bedrock.cancel'))

  const customProblems = check(schema, {
    title: 'Form',
    bedrock: {
      mode: 'CUSTOM',
      fields: [
        { type: 'input', id: 'name', text: 'Name' },
        { type: 'toggle', id: 'name', text: 'Duplicate' },
        { type: 'dropdown', id: 'pick', text: 'Pick', options: ['A'], default: 3 },
        { type: 'slider', id: 'qty', text: 'Qty', min: 10, max: 5, step: 0 },
      ],
    },
  })
  assert.ok(customProblems.some((p) => p.message.includes("duplicate field id 'name'")))
  assert.ok(customProblems.some((p) => p.message.includes('outside 0..0')))
  assert.ok(customProblems.some((p) => p.message.includes('greater than min')))
  assert.ok(customProblems.some((p) => p.message.includes('must be positive')))
})
