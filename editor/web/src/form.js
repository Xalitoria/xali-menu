// Draws the form for any value of the menu format from the schema: text boxes, switches, lists, nested objects, and a choice
// of type for actions, conditions and the like. The form changes the store and nothing else.

import { h, doc } from './dom.js'
import { summary } from './schema.js'
import { render as renderText } from './mctext.js'

/** Keys whose text is shown in the game with colors, so the form previews it. */
const FORMATTED = new Set(['title', 'name', 'item-name', 'lore', 'text', 'subtitle', 'prompt', 'pages', 'invalid', 'message'])

/** The first sentence of a comment, for the line under a label. */
const firstSentence = (text) => (text ?? '').split(/(?<=\.)\s/)[0]

export class Form {
  /**
   * @param {import('./schema.js').Schema} schema
   * @param {import('./store.js').Store} store
   * @param {{icon?: (material: string) => HTMLElement|null}} [hooks] extras the page can provide
   */
  constructor(schema, store, hooks = {}) {
    this.schema = schema
    this.store = store
    this.hooks = hooks
    /** The rows that are open, by path, so a redraw keeps them open. */
    this.open = new Set()
  }

  /** The form for an object of the named type at [path]. */
  object(typeName, path, options = {}) {
    const value = this.store.get(path) ?? {}
    const known = this.schema.fields(typeName)
    const hidden = new Set(options.hide ?? [])
    const present = known.filter((f) => !hidden.has(f.key) && (f.required || f.key in value))
    const absent = known.filter((f) => !hidden.has(f.key) && !f.required && !(f.key in value))
    const extra = Object.keys(value).filter((k) => !known.some((f) => f.key === k) && !(k === 'type' && options.variant))

    const root = h('div', { class: 'object' })
    for (const field of this.ordered(present, options.first)) root.append(this.field(field, [...path, field.key], value[field.key]))
    for (const key of extra) root.append(this.unknown(key, [...path, key], value[key]))
    if (absent.length) root.append(this.adder(absent, path))
    return root
  }

  ordered(fields, first = []) {
    const rank = (f) => { const i = first.indexOf(f.key); return i < 0 ? first.length : i }
    return [...fields].sort((a, b) => rank(a) - rank(b))
  }

  /** One row: a label, the control, and a button to take the key away again. */
  field(field, path, value) {
    const control = this.control(field.type, path, value, field)
    const missing = field.required && value === undefined
    return h('div', { class: `field ${missing ? 'missing' : ''}`, 'data-key': field.key },
      h('label', { title: field.doc ?? '' },
        field.key, field.required && h('span', { class: 'req', title: 'required' }, '*')),
      h('div', { class: 'control' }, control),
      !field.required && h('button', { class: 'icon', title: 'Remove this property', onclick: () => this.store.remove(path) }, '×'),
      field.doc && h('div', { class: 'hint', title: field.doc }, doc(firstSentence(field.doc))),
    )
  }

  /** A key the schema does not know (an addon's, or a typo): shown as JSON so nothing is lost. */
  unknown(key, path, value) {
    return h('div', { class: 'field unknown' },
      h('label', { title: 'Not part of the format that this editor knows' }, key),
      h('div', { class: 'control' }, this.json(path, value)),
      h('button', { class: 'icon', title: 'Remove', onclick: () => this.store.remove(path) }, '×'),
    )
  }

  /** A choice of the keys that are not set yet. */
  adder(absent, path) {
    const select = h('select', { class: 'adder', onchange: () => {
      const field = absent.find((f) => f.key === select.value)
      if (field) this.store.set([...path, field.key], field.default !== undefined ? structuredClone(field.default) : this.schema.blank(field.type))
    } },
      h('option', { value: '' }, '+ Add property'),
      absent.map((f) => h('option', { value: f.key, title: f.doc ?? '' }, f.key)),
    )
    return h('div', { class: 'field add' }, select)
  }

  /** The control for a value of [type]. */
  control(type, path, value, field = {}) {
    switch (type.kind) {
      case 'string': return this.text(path, value, field)
      case 'duration': return this.text(path, value, { ...field, placeholder: '5s, 500ms, 2m' })
      case 'int': case 'number': return this.number(type, path, value, field)
      case 'boolean': return this.checkbox(path, value, field)
      case 'enum': return this.select(type, path, value, field)
      case 'list': return this.list(type.of, path, value ?? [], field)
      case 'map': return this.map(type.of, path, value ?? {})
      case 'object': return this.nested(type.type, path)
      case 'poly': return this.poly(type.base, path, value, field)
      default: return this.json(path, value)
    }
  }

  text(path, value, field) {
    const key = field.key
    const input = h('input', { type: 'text', value: value ?? '', placeholder: field.placeholder ?? (field.default !== undefined ? String(field.default) : ''),
      oninput: () => { this.store.set(path, input.value, true); this.preview(preview, key, input.value) } })
    const preview = h('div', { class: 'preview' })
    this.preview(preview, key, value ?? '')
    return h('div', { class: 'text' }, input, preview)
  }

  preview(element, key, text) {
    element.replaceChildren(FORMATTED.has(key) && text ? renderText(text) : '')
  }

  number(type, path, value, field) {
    const input = h('input', { type: 'number', value: typeof value === 'number' ? value : '', step: type.kind === 'int' ? 1 : 'any',
      placeholder: field.default !== undefined ? String(field.default) : '',
      oninput: () => {
        if (input.value === '') { if (!field.required) this.store.remove(path); return }
        this.store.set(path, Number(input.value), true)
      } })
    return input
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
    const id = path.join('/')
    const value = this.store.get(path)
    const body = this.object(typeName, path)
    return h('details', { class: 'group', open: this.open.has(id) || value === undefined, ontoggle: (e) => (e.target.open ? this.open.add(id) : this.open.delete(id)) },
      h('summary', null, summary(value) || 'empty'), body)
  }

  /** An object of an open base such as Action: pick the type, then fill in its keys. */
  poly(base, path, value, field) {
    const names = this.schema.variants(base)
    const current = value?.type
    const typeName = this.schema.variantType(base, current)
    const select = h('select', { class: 'variant', onchange: () => this.store.set(path, this.schema.blankVariant(base, select.value)) },
      current === undefined && h('option', { value: '' }, 'choose…'),
      current !== undefined && !names.includes(current) && h('option', { value: current, selected: true }, `${current} (unknown)`),
      names.map((n) => h('option', { value: n, selected: n === current }, n)))
    const variantDoc = typeName ? this.schema.type(typeName).doc : null
    return h('div', { class: 'poly' },
      select,
      variantDoc && h('div', { class: 'hint' }, doc(firstSentence(variantDoc))),
      typeName ? this.object(typeName, path, { variant: true }) : current !== undefined && this.json(path, value, ['type']),
    )
  }

  list(of, path, items, field) {
    const rows = h('div', { class: 'list' })
    if (of.kind === 'int') {
      const input = h('input', { type: 'text', value: items.join(', '), placeholder: 'numbers, separated by commas',
        oninput: () => this.store.set(path, input.value.split(/[\s,]+/).filter(Boolean).map(Number).filter((n) => !Number.isNaN(n)), true) })
      return input
    }
    items.forEach((item, index) => rows.append(this.row(of, path, index, item, items.length, field)))
    rows.append(h('button', { class: 'add', onclick: () => this.store.set(path, [...items, this.schema.blank(of)]) }, '+ Add'))
    return rows
  }

  /** One entry of a list: the control, and buttons to move or remove it. */
  row(of, path, index, item, length, field) {
    const rowPath = [...path, index]
    const complex = of.kind === 'object' || of.kind === 'poly' || of.kind === 'map' || of.kind === 'list'
    const id = rowPath.join('/')
    const control = complex
      ? h('details', { class: 'card', open: this.open.has(id), ontoggle: (e) => (e.target.open ? this.open.add(id) : this.open.delete(id)) },
          h('summary', null, summary(item) || 'empty'),
          this.control(of, rowPath, item, field))
      : this.control(of, rowPath, item, { ...field, key: field.key })
    return h('div', { class: 'row' }, control,
      h('div', { class: 'buttons' },
        h('button', { class: 'icon', title: 'Move up', disabled: index === 0, onclick: () => this.store.move(path, index, -1) }, '↑'),
        h('button', { class: 'icon', title: 'Move down', disabled: index === length - 1, onclick: () => this.store.move(path, index, 1) }, '↓'),
        h('button', { class: 'icon', title: 'Remove', onclick: () => this.store.remove(rowPath) }, '×')))
  }

  /** An object of free names to values (the items of a menu). */
  map(of, path, entries) {
    const root = h('div', { class: 'map' })
    for (const key of Object.keys(entries)) {
      const entryPath = [...path, key]
      const id = entryPath.join('/')
      const name = h('input', { type: 'text', class: 'key', value: key, title: 'Name', onchange: () => this.store.rename(path, key, name.value.trim() || key) })
      root.append(h('details', { class: 'card', open: this.open.has(id), ontoggle: (e) => (e.target.open ? this.open.add(id) : this.open.delete(id)) },
        h('summary', null, key, h('span', { class: 'sub' }, ` ${summary(entries[key])}`)),
        h('div', { class: 'field' }, h('label', null, 'name'), h('div', { class: 'control' }, name)),
        this.control(of, entryPath, entries[key]),
        h('button', { class: 'danger', onclick: () => this.store.remove(entryPath) }, 'Remove')))
    }
    root.append(h('button', { class: 'add', onclick: () => {
      let n = 1
      while (`new${n}` in entries) n++
      this.store.set([...path, `new${n}`], this.schema.blank(of))
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
