// Draws the form for any value of the menu format from the schema: text boxes, switches, lists, nested objects, and a choice
// of type for actions, conditions and the like. The form changes the store and nothing else.

import { h, doc } from './dom.js'
import { summary } from './schema.js'
import { render as renderText } from './mctext.js'
import { reorderable } from './reorder.js'
import { flagOf, parseDynamic, isDynamic } from './dynamic.js'
import { editorFor } from './editors.js'
import { closest } from './suggest.js'
import { sentence } from './sentences.js'
import { templateOf, tokens, keysOf } from './inline.js'

/** Keys whose text is shown in the game with colors, so the form previews it. */
const FORMATTED = new Set(['title', 'name', 'item-name', 'lore', 'text', 'subtitle', 'prompt', 'pages', 'invalid', 'message'])

/** The choice of a dropdown of a registry that stands for a value typed by hand. */
const OTHER = '\u0000other'

/** The first sentence of a comment, for the line under a label. */
const firstSentence = (text) => (text ?? '').split(/(?<=\.)\s/)[0]

/** Whether a control of [type] holds other controls (an object, a map, a list of objects), and so is given the whole width. */
function isBlock(type) {
  return ['object', 'map', 'poly'].includes(type.kind) || (type.kind === 'list' && ['object', 'poly', 'map', 'list'].includes(type.of?.kind))
}

export class Form {
  /**
   * @param {import('./schema.js').Schema} schema
   * @param {import('./store.js').Store} store
   * @param {{material?: Function, choices?: (path: Array, field: object) => Array<[string, string]>|null, extraVariants?: (base: string) => string[]}} [hooks] extras the page can provide:
   *   `material` makes the control of an item's material, `choices` gives a text key a fixed set of `[value, label]` to choose from,
   *   `extraVariants` names the types of a base that the schema lacks (a catalog of another plugin)
   */
  constructor(schema, store, hooks = {}) {
    this.schema = schema
    this.store = store
    this.hooks = hooks
    /** The rows that are open, by path, so a redraw keeps them open. */
    this.open = new Set()
    /** The nested objects the user folded: they start open, since what is inside is what is being edited. */
    this.shut = new Set()
    /** The lines that say an action or a condition in words, by path: they are brought up to date as it is typed in, which does not draw the form again. */
    this.sentences = new Map()
    store.onChange(() => this.updateSentences())
  }

  updateSentences() {
    for (const [element, { base, path }] of this.sentences) {
      if (!element.isConnected) { this.sentences.delete(element); continue }
      element.textContent = sentence(base, this.store.get(path)) ?? ''
    }
  }

  /** The form for an object of the named type at [path]. */
  object(typeName, path, options = {}) {
    const value = this.store.get(path) ?? {}
    const known = this.schema.fields(typeName)
    const hidden = new Set(options.hide ?? [])
    // `only` limits the form to some keys, for a form that is split over several places.
    const shown = (f) => !hidden.has(f.key) && (!options.only || options.only.includes(f.key))
    const present = known.filter((f) => shown(f) && (f.required || f.key in value))
    const absent = this.ordered(known.filter((f) => shown(f) && !f.required && !(f.key in value)), options.first)
    const extra = options.only ? [] : Object.keys(value).filter((k) => !known.some((f) => f.key === k) && !(k === 'type' && options.variant))

    const root = h('div', { class: 'object' })
    for (const field of this.ordered(present, options.first)) {
      const fieldPath = [...path, field.key]
      const chain = this.chain(field, fieldPath, value[field.key])
      root.append(this.field(field, fieldPath, value[field.key], chain))
      // The keys that could still be added at the levels the row passes through: they have no row of their own to put an adder in.
      const inner = chain.levels.flatMap((level) => level.absent.map((f) => ({ label: [...level.labels, f.key].join(' › '), path: [...level.path, f.key], field: f })))
      if (inner.length) root.append(this.adder(inner))
    }
    for (const key of extra) root.append(this.unknown(key, [...path, key], value[key], closest(key, known.map((f) => f.key).filter((k) => !(k in value)))))
    if (absent.length) root.append(this.adder(absent.map((f) => ({ label: f.key, path: [...path, f.key], field: f }))))
    return root
  }

  /**
   * An object whose one key holds another object (`click` holds only `left`, which holds only `actions`) is shown as one row, `click › left › actions`, not
   * as a staircase of indents; the keys that could be added at the levels in between go into an adder under it.
   * @returns {{labels: string[], levels: {labels: string[], path: Array, absent: object[]}[], field: object, path: Array, value: *}} the row to draw: the field
   *   at the end of the chain, with its own path and value, and the levels passed through (none when the field is not the start of a chain)
   */
  chain(field, path, value) {
    const labels = [field.key]
    const levels = []
    let at = { field, path, value }
    while (at.field.type.kind === 'object' && !editorFor(at.field)) {
      const known = this.schema.fields(at.field.type.type)
      const object = at.value ?? {}
      const present = known.filter((f) => f.required || f.key in object)
      const stray = Object.keys(object).some((k) => !known.some((f) => f.key === k))
      if (present.length !== 1 || stray || !isBlock(present[0].type)) break
      levels.push({ labels: labels.slice(), path: at.path, absent: known.filter((f) => !f.required && !(f.key in object)) })
      labels.push(present[0].key)
      at = { field: present[0], path: [...at.path, present[0].key], value: object[present[0].key] }
    }
    return { labels, levels, ...at }
  }

  ordered(fields, first = []) {
    const rank = (f) => { const i = first.indexOf(f.key); return i < 0 ? first.length : i }
    return [...fields].sort((a, b) => rank(a) - rank(b))
  }

  /** One row: a label, the control, and a button to take the key away again. [chain] is what [chain] made of it: the row may be several keys deep. */
  field(field, path, value, chain = this.chain(field, path, value)) {
    const end = chain.field
    const control = this.control(end.type, chain.path, chain.value, end)
    const missing = end.required && chain.value === undefined
    // A control that holds other controls (an object, a map, a list of objects) gets the whole width, with its label above it.
    const block = isBlock(end.type) || Boolean(editorFor(end)?.block)
    const deep = chain.levels.length > 0
    // `require › require` (a gate holds a list of the same name) is one name to read.
    const label = chain.labels.filter((l, i, all) => l !== all[i - 1]).join(' › ')
    return h('div', { class: `field ${missing ? 'missing' : ''} ${block ? 'block' : ''} ${deep ? 'chained' : ''}`, 'data-key': field.key },
      h('label', { title: (deep ? end.doc : field.doc) ?? '' },
        label, end.required && h('span', { class: 'req', title: 'required' }, '*')),
      h('div', { class: 'control' }, control),
      !field.required && h('button', { class: 'icon remove', title: deep ? `Remove ${label}` : 'Remove this property', 'aria-label': `Remove ${label}`, onclick: () => this.store.remove(path) }, '×'),
      (deep ? end.doc : field.doc) && h('div', { class: 'hint', title: (deep ? end.doc : field.doc) }, doc(firstSentence(deep ? end.doc : field.doc))),
    )
  }

  /** A key the schema does not know (an addon's, or a typo): shown as JSON so nothing is lost. */
  unknown(key, path, value, suggestion = null) {
    return h('div', { class: 'field unknown' },
      h('label', { title: 'Not part of the format that this editor knows' }, key),
      h('div', { class: 'control' },
        suggestion && h('div', { class: 'did-you-mean' }, 'Not a key of this, so it is ignored. ',
          h('button', { class: 'small', title: `Rename ${key} to ${suggestion}`, onclick: () => this.store.rename(path.slice(0, -1), key, suggestion) }, `Rename to ${suggestion}`)),
        this.json(path, value)),
      h('button', { class: 'icon remove', title: `Remove ${key}`, 'aria-label': `Remove ${key}`, onclick: () => this.store.remove(path) }, '×'),
    )
  }

  /** A choice of the keys that are not set yet: [options] are `{label, path, field}`, the path being where the key goes. */
  adder(options) {
    const select = h('select', { class: 'adder', onchange: () => {
      const option = options[Number(select.value)]
      if (option) this.store.set(option.path, option.field.default !== undefined ? structuredClone(option.field.default) : this.schema.blank(option.field.type))
    } },
      h('option', { value: '' }, '+ Add property'),
      options.map((o, i) => h('option', { value: String(i), title: o.field.doc ?? '' }, o.label)),
    )
    return h('div', { class: 'field add' }, select)
  }

  /** The control for a value of [type]. */
  control(type, path, value, field = {}) {
    // A value that has a control made for it (the patterns of a banner, the bursts of a firework, a color).
    const editor = editorFor(field)
    if (editor) return editor(this.store, path, value, this.store.get(path.slice(0, -1)))
    const choices = type.kind === 'int' || type.kind === 'string' ? this.hooks.choices?.(path, field) : null
    if (choices) return this.choice(path, value, choices, type.kind === 'int')
    const listed = type.kind === 'string' ? this.hooks.registry?.(field) : null
    if (listed) return this.registry(path, value, listed)
    switch (type.kind) {
      case 'string': return this.text(path, value, field)
      case 'duration': return this.text(path, value, { ...field, placeholder: '5s, 500ms, 2m' })
      case 'int': case 'number': return this.number(type, path, value, field)
      case 'boolean': return this.checkbox(path, value, field)
      case 'dynamic': return this.dynamic(type.of, path, value, field)
      case 'enum': return this.select(type, path, value, field)
      case 'list': return this.list(type.of, path, value ?? [], field)
      case 'map': return this.map(type.of, path, value ?? {}, field)
      case 'object': return this.nested(type.type, path)
      case 'poly': return this.poly(type.base, path, value, field)
      default: return this.json(path, value)
    }
  }

  text(path, value, field) {
    // The material of an item is picked from a search of every item.
    if (field.key === 'material' && this.hooks.material) return this.hooks.material(path, value, field)
    const key = field.key
    let preview = null
    const input = h('input', { type: 'text', value: value ?? '', placeholder: field.placeholder ?? (field.default !== undefined ? String(field.default) : ''),
      oninput: () => { this.store.set(path, input.value, true); if (preview) this.preview(preview, key, input.value) } })
    if (field.compact) return input
    preview = h('div', { class: 'preview' })
    this.preview(preview, key, value ?? '')
    return h('div', { class: 'text' }, input, preview)
  }

  /** A text value that is one of a few [choices] (`[value, label]`); a value that is not among them stays as an extra choice so it is not lost. */
  choice(path, value, choices, numeric = false) {
    const known = choices.some(([v]) => v === String(value))
    const select = h('select', { onchange: () => this.store.set(path, numeric ? Number(select.value) : select.value) },
      value !== undefined && !known && h('option', { value, selected: true }, `${value} (not in the list)`),
      choices.map(([v, label]) => h('option', { value: v, selected: v === String(value) }, label)))
    return select
  }

  /**
   * A text value that is one of the [choices] of a registry of the game (`[value, label]`): a dropdown of them, and `Other…` for a value that is not
   * in the list (another plugin's, a datapack's), which is then typed.
   */
  registry(path, value, choices) {
    const custom = typeof value === 'string' && value !== '' && !choices.some(([v]) => v === value)
    const input = h('input', { type: 'text', class: 'registry-other', value: custom ? value : '', placeholder: 'another key, such as mypack:thing', spellcheck: false, hidden: !custom,
      oninput: () => this.store.set(path, input.value.trim(), true) })
    const select = h('select', { onchange: () => {
      input.hidden = select.value !== OTHER
      if (select.value === OTHER) input.focus()
      else this.store.set(path, select.value)
    } },
      value === undefined && h('option', { value: '', selected: true }, 'choose…'),
      choices.map(([v, label]) => h('option', { value: v, selected: v === value }, label.toLowerCase() === v.replace(/_/g, ' ') || label === v ? label : `${label} (${v})`)),
      h('option', { value: OTHER, selected: custom }, 'Other…'))
    return h('div', { class: 'registry' }, select, input)
  }

  preview(element, key, text) {
    element.replaceChildren(FORMATTED.has(key) && text ? renderText(text) : '')
  }

  number(type, path, value, field) {
    const input = h('input', { type: 'number', value: typeof value === 'number' ? value : '', step: type.kind === 'int' ? 1 : 'any', min: field.min, max: field.max,
      placeholder: field.default !== undefined ? String(field.default) : '',
      oninput: () => {
        if (input.value === '') { if (!field.required) this.store.remove(path); return }
        this.store.set(path, Number(input.value), true)
      } })
    return input
  }

  /**
   * A number or a switch that may also be text with placeholders, which the plugin works out when it draws the item. A number is a box that takes a number
   * or a `%placeholder%` and keeps a number a number, so the file reads `amount = 3` until a placeholder is typed. A switch is a checkbox with a `%` button
   * that turns it into a box for the placeholder, and a button that turns it back.
   */
  dynamic(kind, path, value, field) {
    const typed = (input) => () => {
      if (input.value.trim() === '') { if (!field.required) this.store.remove(path); return }
      this.store.set(path, parseDynamic(input.value, kind), true)
    }
    if (kind !== 'boolean') {
      const input = h('input', { type: 'text', class: isDynamic(value, kind) ? 'dynamic text-mode' : 'dynamic', value: value === undefined ? '' : String(value), spellcheck: false,
        placeholder: field.default !== undefined ? String(field.default) : 'a number or a %placeholder%',
        title: `A number${field.min !== undefined || field.max !== undefined ? ` from ${field.min ?? 'any'} to ${field.max ?? 'any'}` : ''}, or a placeholder that works out to one when the item is drawn` })
      input.oninput = () => { input.classList.toggle('text-mode', isDynamic(parseDynamic(input.value, kind), kind)); typed(input)() }
      return input
    }
    const wrap = h('div', { class: 'dynamic-switch' })
    const box = h('input', { type: 'text', class: 'dynamic text-mode', value: typeof value === 'string' ? value : '', spellcheck: false, placeholder: '%placeholder% that gives true or false',
      title: 'A placeholder that works out to true or false when the item is drawn' })
    box.oninput = typed(box)
    const toText = () => { wrap.replaceChildren(box, h('button', { class: 'small', title: 'Use a switch again', onclick: () => this.store.set(path, false) }, 'Switch')); box.focus() }
    const toSwitch = () => wrap.replaceChildren(
      h('input', { type: 'checkbox', checked: flagOf(value, field.default ?? false), onchange: (e) => this.store.set(path, e.target.checked) }),
      h('button', { class: 'small', title: 'Decide with a placeholder instead', onclick: toText }, '%'))
    if (typeof value === 'string') wrap.append(box, h('button', { class: 'small', title: 'Use a switch again', onclick: () => this.store.set(path, false) }, 'Switch'))
    else toSwitch()
    return wrap
  }

  checkbox(path, value, field) {
    return h('input', { type: 'checkbox', checked: value ?? field.default ?? false, onchange: (e) => this.store.set(path, e.target.checked) })
  }

  select(type, path, value, field) {
    const select = h('select', { onchange: () => this.store.set(path, select.value) },
      value === undefined && h('option', { value: '' }, field.default ? `${field.default} (default)` : ''),
      type.values.map((v) => h('option', { value: v, selected: v === value }, v)))
    return select
  }

  nested(typeName, path) {
    const body = this.object(typeName, path)
    // Not folded: it is what is being edited, and a line at its left shows how deep it is.
    return h('div', { class: 'group nested' }, body)
  }

  /** An object of an open base such as Action: pick the type, then fill in its keys. */
  poly(base, path, value, field) {
    const names = [...new Set([...this.schema.variants(base), ...(this.hooks.extraVariants?.(base) ?? [])])].sort()
    const current = value?.type
    const typeName = this.schema.variantType(base, current)
    const select = h('select', { class: 'variant', onchange: () => this.store.set(path, this.schema.blankVariant(base, select.value)) },
      current === undefined && h('option', { value: '' }, 'choose…'),
      current !== undefined && !names.includes(current) && h('option', { value: current, selected: true }, `${current} (unknown)`),
      names.map((n) => h('option', { value: n, selected: n === current }, n)))
    const variantDoc = typeName ? this.schema.type(typeName).doc : null
    // What the type says as a sentence with boxes in it, when it has one: the keys in the sentence are edited there, the others in the form below it.
    const template = typeName ? templateOf(base, current) : null
    const says = template ? this.inlineSentence(template, typeName, path) : h('div', { class: 'sentence' }, sentence(base, value))
    if (!template && ['Action', 'Condition'].includes(base)) this.sentences.set(says, { base, path })
    return h('div', { class: 'poly' },
      select,
      says,
      variantDoc && h('div', { class: 'hint' }, doc(firstSentence(variantDoc))),
      typeName ? this.object(typeName, path, { variant: true, hide: template ? keysOf(template) : [] }) : current !== undefined && this.json(path, value, ['type']),
    )
  }

  /** A sentence ([template], see inline.js) of the object of [typeName] at [path], with the controls of its keys where they are written. */
  inlineSentence(template, typeName, path) {
    const parts = tokens(template).map((token) => {
      if (token.text !== undefined) return h('span', { class: 'words' }, token.text)
      const field = this.schema.field(typeName, token.key)
      if (!field) return h('span', { class: 'words' }, `{${token.key}}`)
      const fieldPath = [...path, token.key]
      const value = this.store.get(fieldPath)
      if (token.on !== undefined && field.type.kind === 'boolean') {
        // The default is not written: a file says only what differs.
        const choose = () => { const on = select.value === 'on'; if (!field.required && on === (field.default ?? false)) this.store.remove(fieldPath); else this.store.set(fieldPath, on) }
        const select = h('select', { class: 'inline-control', onchange: choose },
          h('option', { value: 'off', selected: value !== true }, token.off), h('option', { value: 'on', selected: value === true }, token.on))
        return select
      }
      return h('span', { class: `inline-control ${field.type.kind}` }, this.control(field.type, fieldPath, value, { ...field, compact: true }))
    })
    return h('div', { class: 'inline-sentence' }, parts)
  }

  list(of, path, items, field) {
    const rows = h('div', { class: 'list' })
    if (of.kind === 'int') {
      const input = h('input', { type: 'text', value: items.join(', '), placeholder: 'numbers, separated by commas', title: field.min !== undefined || field.max !== undefined ? `Each from ${field.min ?? 'any'} to ${field.max ?? 'any'}` : '',
        oninput: () => this.store.set(path, input.value.split(/[\s,]+/).filter(Boolean).map(Number).filter((n) => !Number.isNaN(n)), true) })
      return input
    }
    items.forEach((item, index) => rows.append(this.row(of, path, index, item, items.length, field)))
    reorderable(rows, (from, to) => this.store.move(path, from, to - from))
    rows.append(h('button', { class: 'add', onclick: () => this.add(of, path, items) }, '+ Add'))
    return rows
  }

  /** Adds a blank entry to the list at [path]; an entry that is an object opens, since it is to be filled in. */
  add(of, path, items) {
    if (['object', 'poly', 'map', 'list'].includes(of.kind)) this.open.add([...path, items.length].join('/'))
    this.store.set(path, [...items, this.schema.blank(of)])
  }

  /** What a collapsed row says: an action or condition as a sentence, and a check of a gate as the sentence of its condition. */
  rowSummary(of, item) {
    if (of.kind === 'poly') return sentence(of.base, item)
    if (of.kind === 'object' && of.type === 'Check') return `${item?.optional ? 'Optional: ' : ''}${sentence('Condition', item?.condition)}`.replace(/: $/, '')
    return ''
  }

  /** One entry of a list: a grip to drag it by, the control, and a button to remove it. */
  row(of, path, index, item, length, field) {
    const rowPath = [...path, index]
    const complex = of.kind === 'object' || of.kind === 'poly' || of.kind === 'map' || of.kind === 'list'
    const id = rowPath.join('/')
    const control = complex
      ? h('details', { class: 'card', open: this.open.has(id), ontoggle: (e) => (e.target.open ? this.open.add(id) : this.open.delete(id)) },
          h('summary', null, this.rowSummary(of, item) || summary(item) || 'empty'),
          this.control(of, rowPath, item, field))
      : this.control(of, rowPath, item, { ...field, key: field.key })
    return h('div', { class: 'row' },
      length > 1 && h('button', { class: 'grip', title: 'Drag to move (Alt + Up or Down on the keyboard)', 'aria-label': 'Move' }, '⠿'),
      control,
      h('div', { class: 'buttons' },
        h('button', { class: 'icon remove', title: 'Remove this entry', 'aria-label': 'Remove this entry', onclick: () => this.store.remove(rowPath) }, '×')))
  }

  /** An object of free names to values (the items of a menu, or the enchantments of an item, whose names are chosen from the registry). */
  map(of, path, entries, field = {}) {
    const root = h('div', { class: 'map' })
    const choices = this.hooks.registry?.(field) ?? null
    for (const key of Object.keys(entries)) {
      const entryPath = [...path, key]
      const id = entryPath.join('/')
      const rename = (to) => this.store.rename(path, key, to || key)
      // A name from a registry is chosen from its list, unless it is not in it (typed by hand, or a plugin's).
      const name = choices && choices.some(([v]) => v === key)
        ? h('select', { class: 'key', title: 'Name', onchange: () => rename(name.value) },
          choices.filter(([v]) => v === key || !(v in entries)).map(([v, label]) => h('option', { value: v, selected: v === key }, label)))
        : h('input', { type: 'text', class: 'key', value: key, title: 'Name', onchange: () => rename(name.value.trim()) })
      root.append(h('details', { class: 'card entry', open: !this.shut.has(id), ontoggle: (e) => (e.target.open ? this.shut.delete(id) : this.shut.add(id)) },
        h('summary', null, h('b', null, key), h('span', { class: 'sub' }, ` ${summary(entries[key])}`)),
        h('div', { class: 'entry-name' }, name, h('button', { class: 'icon remove', title: `Remove ${key}`, 'aria-label': `Remove ${key}`, onclick: () => this.store.remove(entryPath) }, '×')),
        this.control(of, entryPath, entries[key])))
    }
    root.append(h('button', { class: 'add', onclick: () => {
      let n = 1
      let name = choices?.map(([v]) => v).find((v) => !(v in entries))
      while (!name) { if (!(`new${n}` in entries)) name = `new${n}`; n++ }
      this.store.set([...path, name], this.schema.blank(of))
    } }, '+ Add'))
    return root
  }

  /** Any value as JSON text, for what the schema cannot describe. */
  json(path, value, without = []) {
    const shown = value && typeof value === 'object' && !Array.isArray(value) ? Object.fromEntries(Object.entries(value).filter(([k]) => !without.includes(k))) : value
    const area = h('textarea', { class: 'json', rows: 3, value: JSON.stringify(shown, null, 2) ?? '',
      onchange: () => {
        try {
          const parsed = JSON.parse(area.value)
          this.store.set(path, without.length ? { ...Object.fromEntries(without.map((k) => [k, value[k]])), ...parsed } : parsed)
          area.classList.remove('bad')
        } catch { area.classList.add('bad') }
      } })
    return area
  }
}
