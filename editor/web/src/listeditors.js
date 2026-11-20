// The effects of a potion and the modifiers of an item's attributes, as rows that say what they do (seconds and levels, a share of the base) rather
// than a form of ticks and a level minus one. Like the other editors in `editors.js`, a control changes the store and nothing else.

import { h } from './dom.js'
import { icon } from './icons.js'
import { registries } from './registry.js'
import { EFFECT_COLORS } from './potions.js'
import { numberOf } from './dynamic.js'
import { render as renderText, plain } from './mctext.js'
import { roman, levelOf, amplifierOf, secondsOf, ticksOf, clock, OPERATIONS, effectOf } from './effects.js'

const words = (id) => String(id).replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())
const bare = (id) => String(id ?? '').trim().toLowerCase().replace(/^minecraft:/, '')

/** A choice among `[value, label]`; a value that is not among them stays as it is, and can be typed under "Other…". */
function choose(choices, current, change, { placeholder = 'choose…', other = 'another key, such as mypack:thing', label: aria } = {}) {
  // With no list to choose from (the game's names are not loaded) the key is typed.
  if (choices.length === 0) {
    const input = h('input', { type: 'text', class: 'registry-other', value: current ?? '', placeholder: other, spellcheck: false, 'aria-label': aria, oninput: () => change(input.value.trim(), true) })
    return h('span', { class: 'registry' }, input)
  }
  const known = choices.some(([v]) => v === current)
  const custom = current !== undefined && current !== '' && !known
  const input = h('input', { type: 'text', class: 'registry-other', value: custom ? current : '', placeholder: other, spellcheck: false, hidden: !custom,
    oninput: () => change(input.value.trim(), true) })
  const select = h('select', { 'aria-label': aria, onchange: () => {
    input.hidden = select.value !== '\u0000other'
    if (select.value === '\u0000other') input.focus(); else change(select.value, false)
  } },
  (current === undefined || current === '') && h('option', { value: '', selected: true }, placeholder),
  choices.map(([v, name]) => h('option', { value: v, selected: v === current }, name)),
  h('option', { value: '\u0000other', selected: custom }, 'Other…'))
  return h('span', { class: 'registry' }, select, input)
}

/** A number box that changes the store while it is typed in without drawing the form again. */
function numberBox(value, change, attributes) {
  const input = h('input', { type: 'number', value: value ?? '', ...attributes })
  input.addEventListener('input', () => { if (input.value !== '') change(Number(input.value)) })
  return input
}

// ---- potion effects

/** Each effect of a potion: which, for how long in seconds, and its level in the numerals the game uses. */
export function potionEffects(store, path, value, item) {
  const effects = Array.isArray(value) ? value : []
  const liveList = () => { const now = store.get(path); return Array.isArray(now) ? now : effects }
  const set = (next, quiet = false) => store.set(path, next, quiet)
  const choices = registries.get('effect') ?? []
  const rows = effects.map((effect, index) => {
    // The list as it is now: a box typed in does not draw the form again, so what this row was drawn from may be behind.
    const put = (change, quiet = false) => set(liveList().map((e, i) => (i === index ? { ...e, ...change } : e)), quiet)
    // What is being typed, kept here: the form is not drawn again while a box has the focus.
    let duration = numberOf(effect.duration, 0)
    let amplifier = numberOf(effect.amplifier, 0)
    const note = h('small', { class: 'muted effect-note' })
    const describe = () => { note.textContent = `${roman(levelOf(amplifier))} for ${clock(duration)}` }
    describe()
    const dot = EFFECT_COLORS[bare(effect.type)]
    return h('div', { class: 'effect-row' },
      h('i', { class: 'dot', title: 'The color it gives a potion', style: `background:${dot === undefined ? '#888' : `#${dot.toString(16).padStart(6, '0')}`}` }),
      choose(choices, effect.type, (v, quiet) => put({ type: v }, quiet), { label: 'Effect' }),
      h('label', { title: 'How long it lasts, in seconds: 20 ticks are one second' }, h('span', { class: 'muted' }, 'Seconds '),
        numberBox(secondsOf(duration), (n) => { duration = ticksOf(n); put({ duration }, true); describe() }, { min: 0, step: 0.05, class: 'short', 'aria-label': 'Seconds' })),
      h('label', { title: 'The level: I is the plain effect' }, h('span', { class: 'muted' }, 'Level '),
        numberBox(levelOf(amplifier), (n) => { amplifier = amplifierOf(n); put({ amplifier }, true); describe() }, { min: 1, max: 256, step: 1, class: 'short', 'aria-label': 'Level' })),
      note,
      h('span', { class: 'spacer' }),
      h('button', { class: 'icon remove small', title: 'Remove this effect', 'aria-label': 'Remove this effect', onclick: () => set(liveList().filter((_, i) => i !== index)) }, '×'))
  })
  return h('div', { class: 'effects-editor' },
    h('div', { class: 'banner-preview' }, icon(item, 4), h('small', { class: 'muted' }, effects.length === 0 ? 'No custom effects: only what the potion type gives.' : `${effects.length} ${effects.length === 1 ? 'effect' : 'effects'} on top of the potion type`)),
    h('div', { class: 'layers' }, rows,
      h('button', { class: 'add', onclick: () => set([...liveList(), { type: choices[0]?.[0] ?? 'speed', duration: 600, amplifier: 0 }]) }, '+ Add effect')))
}

// ---- attributes

/** Each modifier of an item's attributes: which attribute, what it does to it, by how much, and where the item must be for it to count. */
export function attributeModifiers(store, path, value) {
  const list = Array.isArray(value) ? value : []
  const liveList = () => { const now = store.get(path); return Array.isArray(now) ? now : list }
  const set = (next, quiet = false) => store.set(path, next, quiet)
  const attributes = registries.get('attribute') ?? []
  const slots = registries.get('attribute-slot') ?? []
  const rows = list.map((modifier, index) => {
    const put = (change, quiet = false) => set(liveList().map((m, i) => (i === index ? { ...m, ...change } : m)), quiet)
    let operation = modifier.operation ?? 'ADD'
    let amount = numberOf(modifier.amount, 0)
    const what = h('small', { class: 'muted effect-note' }, effectOf(operation, amount))
    return h('div', { class: 'layer' },
      h('div', { class: 'layer-head' },
        choose(attributes, modifier.attribute, (v, quiet) => put({ attribute: v }, quiet), { label: 'Attribute' }),
        h('span', { class: 'spacer' }),
        h('button', { class: 'icon small', title: 'Move it up', disabled: index === 0, onclick: () => store.move(path, index, -1) }, '↑'),
        h('button', { class: 'icon small', title: 'Move it down', disabled: index === list.length - 1, onclick: () => store.move(path, index, 1) }, '↓'),
        h('button', { class: 'icon remove small', title: 'Remove this modifier', 'aria-label': 'Remove this modifier', onclick: () => set(liveList().filter((_, i) => i !== index)) }, '×')),
      h('div', { class: 'burst-row' },
        h('select', { 'aria-label': 'What it does', title: OPERATIONS.find(([v]) => v === operation)?.[2], onchange: (e) => { operation = e.target.value; put({ operation }) } },
          OPERATIONS.map(([v, name]) => h('option', { value: v, title: name, selected: v === operation }, name))),
        numberBox(amount, (n) => { amount = n; put({ amount }, true); what.textContent = effectOf(operation, amount) }, { step: 'any', class: 'short', 'aria-label': 'Amount' }),
        what),
      h('div', { class: 'burst-row' }, h('span', { class: 'muted' }, 'While in'),
        choose(slots, modifier.slot ?? 'any', (v, quiet) => put({ slot: v }, quiet), { label: 'Slot', other: 'a slot, such as mainhand' })))
  })
  return h('div', { class: 'effects-editor' },
    h('div', { class: 'layers' }, rows,
      h('button', { class: 'add', onclick: () => set([...liveList(), { attribute: attributes[0]?.[0] ?? 'attack_damage', amount: 1 }]) }, '+ Add modifier')))
}

// ---- books

/**
 * The pages of a book, as the reader sees them: a text box and the page as it is drawn (colors and all) side by side, in order, with the buttons to move a page
 * and take it out. Used for the pages of a written book item and of the `book` action. [pages] is what is stored at [path].
 */
export function bookPages(store, path, value) {
  const pages = Array.isArray(value) ? value : []
  const liveList = () => { const now = store.get(path); return Array.isArray(now) ? now : pages }
  const set = (next, quiet = false) => store.set(path, next, quiet)
  const rows = pages.map((text, index) => {
    const sheet = h('div', { class: 'book-page' })
    const count = h('small', { class: 'muted' })
    const show = (now) => { sheet.replaceChildren(renderText(now)); count.textContent = `${plain(now).length} characters` }
    show(String(text ?? ''))
    const box = h('textarea', { rows: 6, spellcheck: false, 'aria-label': `Page ${index + 1}`, placeholder: 'The text of the page. &c gives color, a new line is a new line.' }, String(text ?? ''))
    box.addEventListener('input', () => { set(liveList().map((p, i) => (i === index ? box.value : p)), true); show(box.value) })
    return h('div', { class: 'layer' },
      h('div', { class: 'layer-head' },
        h('b', null, `Page ${index + 1}`), count, h('span', { class: 'spacer' }),
        h('button', { class: 'icon small', title: 'Move it earlier', disabled: index === 0, onclick: () => store.move(path, index, -1) }, '↑'),
        h('button', { class: 'icon small', title: 'Move it later', disabled: index === pages.length - 1, onclick: () => store.move(path, index, 1) }, '↓'),
        h('button', { class: 'icon remove small', title: 'Remove this page', 'aria-label': 'Remove this page', onclick: () => set(liveList().filter((_, i) => i !== index)) }, '×')),
      h('div', { class: 'book-row' }, box, sheet))
  })
  return h('div', { class: 'effects-editor' },
    h('div', { class: 'layers' }, rows,
      h('button', { class: 'add', onclick: () => set([...liveList(), '']) }, '+ Add page')))
}
