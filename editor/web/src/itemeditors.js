// Controls for a food, a tool and a bucket of fish: the numbers drawn as what they are (drumsticks), the rules of a tool as cards with the usual tags to add, a fish with
// its dyes. Like the other editors, a control changes the store and nothing else, and reads the object as it is now (a box typed in does not draw the form again).

import { h } from './dom.js'
import { icon } from './icons.js'
import { registries } from './registry.js'
import { numberOf } from './dynamic.js'
import { dyes } from './dyes.js'
import { choose, numberBox } from './listeditors.js'

/** Changes the keys of the object at [path] that are in [change]; a key whose value is undefined is taken out. */
function patcher(store, path) {
  return (change, quiet = false) => {
    const next = { ...(store.get(path) ?? {}), ...change }
    for (const key of Object.keys(change)) if (change[key] === undefined) delete next[key]
    store.set(path, next, quiet)
  }
}

/** The ten drumsticks of the food bar for [nutrition] (two points a drumstick): `full`, `half` or `empty`, and how many points are past the bar. */
export function shanks(nutrition) {
  const points = Math.max(0, Math.floor(Number(nutrition) || 0))
  return { sticks: Array.from({ length: 10 }, (_, i) => (points >= 2 * (i + 1) ? 'full' : points === 2 * i + 1 ? 'half' : 'empty')), over: Math.max(0, points - 20) }
}

/** What a food restores, as the bar the player sees. */
export function foodEditor(store, path, value, item) {
  const put = patcher(store, path)
  const bar = h('div', { class: 'shanks' })
  const note = h('small', { class: 'muted' })
  const draw = (nutrition, saturation) => {
    const { sticks, over } = shanks(nutrition)
    bar.replaceChildren(...sticks.map((kind) => h('i', { class: `shank ${kind}` })), over > 0 && h('small', { class: 'muted' }, `+${over}`))
    note.textContent = `Restores ${Number(nutrition) / 2 || 0} drumsticks${saturation !== undefined ? ` and ${saturation} saturation` : ''}`
  }
  let nutrition = numberOf(value?.nutrition, 0)
  let saturation = value?.saturation === undefined ? undefined : numberOf(value.saturation, 0)
  draw(nutrition, saturation)
  const saturationBox = h('input', { type: 'number', min: 0, step: 'any', value: saturation ?? '', placeholder: 'by nutrition', class: 'short', 'aria-label': 'Saturation' })
  saturationBox.addEventListener('input', () => { saturation = saturationBox.value === '' ? undefined : Number(saturationBox.value); put({ saturation }, true); draw(nutrition, saturation) })
  return h('div', { class: 'effects-editor' },
    h('div', { class: 'banner-preview' }, icon(item, 4), h('div', null, bar, note)),
    h('div', { class: 'burst-row' },
      h('label', { title: 'Food points: 2 are one drumstick' }, h('span', { class: 'muted' }, 'Nutrition '), numberBox(nutrition, (n) => { nutrition = n; put({ nutrition }, true); draw(nutrition, saturation) }, { min: 0, step: 1, class: 'short', 'aria-label': 'Nutrition' })),
      h('label', { title: 'How long the food bar stays full after: added to the player\'s, not a multiple' }, h('span', { class: 'muted' }, 'Saturation '), saturationBox),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: value?.['always-edible'] === true, onchange: (e) => put({ 'always-edible': e.target.checked }) }), 'Can be eaten when full')))
}

/** The tags the game has for what each tool mines, to add to a rule at a click. */
export const TOOL_TAGS = [
  ['Pickaxe', '#minecraft:mineable/pickaxe'], ['Axe', '#minecraft:mineable/axe'], ['Shovel', '#minecraft:mineable/shovel'], ['Hoe', '#minecraft:mineable/hoe'], ['Sword', '#minecraft:sword_efficient']]

/** The rules of a tool: which blocks, how fast, and whether they drop. */
export function toolEditor(store, path, value) {
  const put = patcher(store, path)
  const rules = () => store.get([...path, 'rules']) ?? []
  const setRules = (next, quiet = false) => put({ rules: next }, quiet)
  const cards = (Array.isArray(value?.rules) ? value.rules : []).map((rule, index) => {
    const edit = (change, quiet = false) => setRules(rules().map((r, i) => {
      if (i !== index) return r
      const next = { ...r, ...change }
      for (const key of Object.keys(change)) if (change[key] === undefined) delete next[key]
      return next
    }), quiet)
    const blocks = () => rules()[index]?.blocks ?? []
    const add = h('input', { type: 'text', class: 'registry-other', placeholder: 'a block such as stone, or a tag such as #minecraft:mineable/pickaxe', spellcheck: false, 'aria-label': 'Add a block' })
    const take = () => { const text = add.value.trim(); if (text) edit({ blocks: [...blocks(), text] }) }
    add.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); take() } })
    add.addEventListener('change', take)
    const drops = rule => (rule.drops === undefined ? '' : rule.drops ? 'yes' : 'no')
    return h('div', { class: 'layer' },
      h('div', { class: 'layer-head' }, h('b', null, `Rule ${index + 1}`), h('span', { class: 'spacer' }),
        h('button', { class: 'icon remove small', title: 'Remove this rule', 'aria-label': 'Remove this rule', onclick: () => setRules(rules().filter((_, i) => i !== index)) }, '×')),
      h('div', { class: 'color-chips' }, (rule.blocks ?? []).map((block) => h('span', { class: 'chip' }, h('span', { class: 'chip-label' }, block),
        h('button', { class: 'chip-x remove', title: `Remove ${block}`, 'aria-label': `Remove ${block}`, onclick: () => edit({ blocks: blocks().filter((b) => b !== block) }) }, '×'))),
      (rule.blocks ?? []).length === 0 && h('span', { class: 'muted' }, 'It needs a block.')),
      h('div', { class: 'burst-row' }, add, TOOL_TAGS.filter(([, tag]) => !(rule.blocks ?? []).includes(tag)).map(([name, tag]) => h('button', { class: 'small', title: tag, onclick: () => edit({ blocks: [...blocks(), tag] }) }, `+ ${name}`))),
      h('div', { class: 'burst-row' },
        h('label', { title: 'The mining speed on these blocks; 1 is the speed of a hand. Empty leaves the speed of the tool' }, h('span', { class: 'muted' }, 'Speed '),
          h('input', { type: 'number', min: 0, step: 'any', class: 'short', value: rule.speed ?? '', 'aria-label': 'Speed', oninput: (e) => edit({ speed: e.target.value === '' ? undefined : Number(e.target.value) }, true) })),
        h('label', null, h('span', { class: 'muted' }, 'Drops '),
          h('select', { 'aria-label': 'Drops', onchange: (e) => edit({ drops: e.target.value === '' ? undefined : e.target.value === 'yes' }) },
            [['', 'as the game decides'], ['yes', 'the blocks drop'], ['no', 'nothing drops']].map(([v, name]) => h('option', { value: v, selected: v === drops(rule) }, name))))))
  })
  const number = (key, label, title, props) => h('label', { title }, h('span', { class: 'muted' }, `${label} `),
    h('input', { type: 'number', step: 'any', class: 'short', 'aria-label': label, value: value?.[key] ?? '', placeholder: props.placeholder, min: props.min,
      oninput: (e) => put({ [key]: e.target.value === '' ? undefined : Number(e.target.value) }, true) }))
  return h('div', { class: 'effects-editor' },
    h('div', { class: 'burst-row' },
      number('mining-speed', 'Speed', 'The mining speed for blocks no rule covers; 1 is the speed of a hand', { placeholder: '1' }),
      number('damage-per-block', 'Wear', 'The durability it loses for each block mined', { placeholder: '1', min: 0 }),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: value?.['can-destroy-in-creative'] !== false, onchange: (e) => put({ 'can-destroy-in-creative': e.target.checked }) }), 'Breaks blocks in creative')),
    h('div', { class: 'layers' }, cards, h('button', { class: 'add', onclick: () => setRules([...rules(), { blocks: [] }]) }, '+ Add rule')))
}

/** A bucket of tropical fish: how it looks, with the pattern from a list and the two dyes. */
export function fishEditor(store, path, value, item) {
  const put = patcher(store, path)
  const patterns = registries.get('fish-pattern') ?? []
  return h('div', { class: 'effects-editor' },
    h('div', { class: 'banner-preview' }, icon(item, 4), h('small', { class: 'muted' }, 'the bucket as it is drawn')),
    h('div', { class: 'burst-row' }, h('span', { class: 'muted' }, 'Pattern'), choose(patterns, value?.pattern, (v, quiet) => put({ pattern: v }, quiet), { label: 'Pattern', other: 'a pattern, such as KOB' })),
    h('div', { class: 'burst-row' }, h('span', { class: 'muted' }, 'Body'), dyes(value?.['body-color'] ?? 'WHITE', (c) => put({ 'body-color': c }), { upper: true })),
    h('div', { class: 'burst-row' }, h('span', { class: 'muted' }, 'Pattern color'), dyes(value?.['pattern-color'] ?? 'WHITE', (c) => put({ 'pattern-color': c }), { upper: true })))
}
