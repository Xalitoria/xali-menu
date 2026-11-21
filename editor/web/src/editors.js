// Controls made for one kind of value, where a list of text boxes would not show what the value is: the patterns of a banner, the bursts of a firework,
// a color. The form asks [editorFor] for a field and puts what it builds in place of the generic control; the control changes the store and nothing else.

import { h } from './dom.js'
import { icon } from './icons.js'
import { registries } from './registry.js'
import { numberOf } from './dynamic.js'
import { potionEffects, attributeModifiers, bookPages } from './listeditors.js'
import { foodEditor, toolEditor, fishEditor, trimEditor } from './itemeditors.js'
import { DYES, label, dyes } from './dyes.js'
import { matrixEditor } from './layouteditor.js'

export { DYES, label }

/** The shapes of a firework burst, as the menu file names them. */
export const SHAPES = [['BALL', 'Small ball'], ['BALL_LARGE', 'Large ball'], ['STAR', 'Star'], ['BURST', 'Burst'], ['CREEPER', 'Creeper']]

/** `#rrggbb` for [value] when it is that, else [fallback]. */
export const hexOf = (value, fallback = '#ff0000') => (/^#[0-9a-f]{6}$/i.test(String(value ?? '')) ? String(value).toLowerCase() : fallback)

/** A box for a color: the picker of the browser, and the text, which also takes what the picker cannot (`#RRGGBB` in any case, typed). */
export function colorBox(value, change, { final = () => {}, placeholder = '#rrggbb' } = {}) {
  const picker = h('input', { type: 'color', class: 'color-picker', value: hexOf(value), 'aria-label': 'Pick a color' })
  const text = h('input', { type: 'text', class: 'color-text', value: value ?? '', placeholder, spellcheck: false, 'aria-label': 'Color as #rrggbb' })
  picker.addEventListener('input', () => { text.value = picker.value; change(picker.value) })
  picker.addEventListener('change', () => final())
  text.addEventListener('input', () => { if (/^#[0-9a-f]{6}$/i.test(text.value)) picker.value = text.value.toLowerCase(); change(text.value.trim()) })
  text.addEventListener('change', () => final())
  return h('div', { class: 'color-box' }, picker, text)
}

/** A row of colors that can be added to, changed and taken out of: [values] are `#rrggbb`, and [set] is given the new list (`quiet` for a drag in the picker). */
export function colorChips(values, set, { empty = 'No color yet.' } = {}) {
  const chips = values.map((value, index) => {
    const picker = h('input', { type: 'color', class: 'chip-color', value: hexOf(value), title: `${hexOf(value)} (click to change)` })
    picker.addEventListener('input', () => set(values.map((v, i) => (i === index ? picker.value : v)), true))
    picker.addEventListener('change', () => set(values.map((v, i) => (i === index ? picker.value : v)), false))
    return h('span', { class: 'color-chip' }, picker,
      h('button', { class: 'icon remove small', title: 'Remove this color', 'aria-label': 'Remove this color', onclick: () => set(values.filter((_, i) => i !== index), false) }, '×'))
  })
  return h('div', { class: 'color-chips' }, chips, values.length === 0 && h('span', { class: 'muted' }, empty),
    h('button', { class: 'small', title: 'Add a color', onclick: () => set([...values, '#ff0000'], false) }, '+ Color'))
}

// ---- banners

/** The patterns to offer: those of the language file, else the ones the game has. */
const FALLBACK_PATTERNS = ['base', 'square_bottom_left', 'square_bottom_right', 'square_top_left', 'square_top_right', 'stripe_bottom', 'stripe_top', 'stripe_left', 'stripe_right',
  'stripe_center', 'stripe_middle', 'stripe_downright', 'stripe_downleft', 'small_stripes', 'cross', 'straight_cross', 'triangle_bottom', 'triangle_top', 'triangles_bottom', 'triangles_top',
  'diagonal_left', 'diagonal_up_right', 'diagonal_up_left', 'diagonal_right', 'circle', 'rhombus', 'half_vertical', 'half_horizontal', 'half_vertical_right', 'half_horizontal_bottom',
  'border', 'curly_border', 'gradient', 'gradient_up', 'bricks', 'globe', 'creeper', 'skull', 'flower', 'mojang', 'piglin', 'flow', 'guster']

/** The patterns as `[id, name]`. */
const patterns = () => registries.get('banner-pattern') ?? FALLBACK_PATTERNS.map((id) => [id, label(id)])

/** A banner that shows [pattern] in black on white, as small as a slot: what the pattern looks like. */
const swatch = (pattern, scale = 2) => icon({ material: 'white_banner', banner: [{ color: 'black', pattern }] }, scale)

/**
 * The layers of a banner, a shield or the like, bottom layer first: the item as it is drawn with them, and for each layer its color (a row of the dyes), its
 * pattern (a gallery of how each looks) and buttons to move it and take it out.
 */
function banner(store, path, value, item) {
  const layers = Array.isArray(value) ? value : []
  const opened = banner.open ??= new Set()
  const key = path.join('/')
  const set = (next, quiet = false) => store.set(path, next, quiet)
  const rows = layers.map((layer, index) => {
    const id = `${key}/${index}`
    // The gallery opens and shuts in place: it is the page's, not the menu's, so the form is not drawn again (and the other galleries stay as they are).
    const gallery = h('div', { class: 'gallery', hidden: !opened.has(id) })
    const fillGallery = () => {
      if (gallery.childElementCount) return
      gallery.append(...patterns().map(([pattern, name]) => h('button', {
        class: `tile ${pattern === layer.pattern ? 'on' : ''}`, title: name, onclick: () => { opened.delete(id); set(layers.map((l, i) => (i === index ? { ...l, pattern } : l))) } },
        swatch(pattern), h('small', null, name))))
    }
    if (opened.has(id)) fillGallery()
    return h('div', { class: 'layer' },
      h('div', { class: 'layer-head' },
        h('span', { class: 'layer-n muted' }, index + 1),
        h('button', { class: 'pattern-button', title: 'Choose the pattern', onclick: () => {
          gallery.hidden = !gallery.hidden
          if (gallery.hidden) opened.delete(id); else { opened.add(id); fillGallery() }
        } }, swatch(layer.pattern), h('span', null, label(String(layer.pattern ?? 'choose a pattern')))),
        h('span', { class: 'spacer' }),
        h('button', { class: 'icon small', title: 'Move it up, over the layers below', disabled: index === 0, onclick: () => store.move(path, index, -1) }, '↑'),
        h('button', { class: 'icon small', title: 'Move it down, under the layers above', disabled: index === layers.length - 1, onclick: () => store.move(path, index, 1) }, '↓'),
        h('button', { class: 'icon remove small', title: 'Remove this layer', 'aria-label': 'Remove this layer', onclick: () => set(layers.filter((_, i) => i !== index)) }, '×')),
      dyes(layer.color, (color) => set(layers.map((l, i) => (i === index ? { ...l, color } : l)))),
      gallery)
  })
  return h('div', { class: 'banner-editor' },
    h('div', { class: 'banner-preview' }, icon(item, 5), h('small', { class: 'muted' }, layers.length === 0 ? 'No layers: the plain cloth.' : `${layers.length} ${layers.length === 1 ? 'layer' : 'layers'}, the first at the bottom`)),
    h('div', { class: 'layers' }, rows,
      h('button', { class: 'add', onclick: () => set([...layers, { color: layers.at(-1)?.color === 'black' ? 'white' : 'black', pattern: patterns()[0]?.[0] ?? 'stripe_top' }]) }, '+ Add layer')))
}

// ---- fireworks

/** The bursts of a firework and how far it flies. */
function firework(store, path, value) {
  const effects = Array.isArray(value?.effects) ? value.effects : []
  const power = numberOf(value?.power, 1)
  const liveEffects = () => store.get(path)?.effects ?? effects
  const set = (next, quiet = false) => store.set(path, { ...store.get(path), ...next }, quiet)
  const burst = (effect, index) => {
    const put = (change, quiet = false) => set({ effects: liveEffects().map((e, i) => (i === index ? { ...e, ...change } : e)) }, quiet)
    const colors = Array.isArray(effect.colors) ? effect.colors : []
    const fade = Array.isArray(effect.fade) ? effect.fade : []
    return h('div', { class: 'burst' },
      h('div', { class: 'burst-head' },
        h('b', null, `Burst ${index + 1}`),
        h('span', { class: 'burst-colors' }, colors.map((c) => h('i', { class: 'dot', style: `background:${hexOf(c, '#888')}` }))),
        h('span', { class: 'spacer' }),
        h('button', { class: 'icon remove small', title: 'Remove this burst', 'aria-label': 'Remove this burst', onclick: () => set({ effects: liveEffects().filter((_, i) => i !== index) }) }, '×')),
      h('div', { class: 'shapes', role: 'radiogroup' }, SHAPES.map(([shape, name]) => h('button', {
        class: `small ${(effect.type ?? 'BALL') === shape ? 'on' : ''}`, role: 'radio', 'aria-checked': (effect.type ?? 'BALL') === shape, onclick: () => put({ type: shape }) }, name))),
      h('div', { class: 'burst-row' }, h('span', { class: 'muted' }, 'Colors'), colorChips(colors, (next, quiet) => put({ colors: next }, quiet), { empty: 'It needs a color.' })),
      h('div', { class: 'burst-row' }, h('span', { class: 'muted' }, 'Fades to'), colorChips(fade, (next, quiet) => put({ fade: next }, quiet), { empty: 'No fade.' })),
      h('div', { class: 'burst-row' },
        h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: effect.flicker === true, onchange: (e) => put({ flicker: e.target.checked }) }), 'Twinkles'),
        h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: effect.trail === true, onchange: (e) => put({ trail: e.target.checked }) }), 'Leaves a trail')))
  }
  const rocket = h('input', { type: 'number', min: 0, max: 127, step: 1, value: power, class: 'power', 'aria-label': 'Flight duration' })
  rocket.addEventListener('input', () => { if (rocket.value !== '') set({ power: Number(rocket.value) }, true) })
  return h('div', { class: 'firework-editor' },
    h('div', { class: 'burst-row' }, h('label', { title: 'How long a rocket flies before it bursts, 1 to 3' }, h('span', { class: 'muted' }, 'Flight '), rocket), h('small', { class: 'muted' }, 'for a rocket; a star shows its first burst')),
    effects.map(burst),
    h('button', { class: 'add', onclick: () => set({ effects: [...liveEffects(), { type: 'BALL', colors: ['#ff0000'] }] }) }, '+ Add burst'))
}

// ---- colors of an item

/** What the form puts in place of its own control, by the type and key of the field: a function `(path, value, store, item) => element`, with `block` when it wants the width. */
const EDITORS = {
  'Item.banner': Object.assign((store, path, value, item) => banner(store, path, value, item), { block: true }),
  'Item.firework': Object.assign((store, path, value) => firework(store, path, value), { block: true }),
  'Item.potion': Object.assign((store, path, value, item) => potionEffects(store, path, value, item), { block: true }),
  'Item.attributes': Object.assign((store, path, value) => attributeModifiers(store, path, value), { block: true }),
  'Book.pages': Object.assign((store, path, value) => bookPages(store, path, value), { block: true }),
  'Action:book.pages': Object.assign((store, path, value) => bookPages(store, path, value), { block: true }),
  'Item.food': Object.assign((store, path, value, item) => foodEditor(store, path, value, item), { block: true }),
  'Item.tool': Object.assign((store, path, value) => toolEditor(store, path, value), { block: true }),
  'Item.fish': Object.assign((store, path, value, item) => fishEditor(store, path, value, item), { block: true }),
  'Item.trim': Object.assign((store, path, value, item) => trimEditor(store, path, value, item), { block: true }),
  'Menu.matrix': Object.assign((store, path, value, menu) => matrixEditor(store, path, value, menu), { block: true }),
  'Item.color': (store, path, value) => colorBox(value, (v) => (v === '' ? store.remove(path) : store.set(path, v, true))),
}

/** The editor for [field] (a field of the schema with its `owner`), or null: a function `(store, path, value, item) => element`. */
export function editorFor(field) {
  return (field?.owner && EDITORS[`${field.owner}.${field.key}`]) || null
}
