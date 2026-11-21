// Finds what is wrong with a menu without asking the server: a missing required key, a key the format does not have, a value of
// the wrong kind, and a slot that is outside the window. The plugin does the same and more when it reads the file, and the
// editor shows what it says too; this is for the problems that can be shown while typing.

import { numberOf, flagOf } from './dynamic.js'
import { closest, hint } from './suggest.js'
import { normalize } from './normalize.js'

const SIZES = { hopper: 5, dispenser: 9, dropper: 9, crafter: 9, shulker_box: 27 }

/** The number of slots of a menu, from its size or its inventory type. */
export function slotCount(menu) {
  const type = String(menu.type ?? 'chest').toLowerCase()
  return type === 'chest' ? (menu.size ?? 54) : (SIZES[type] ?? menu.size ?? 54)
}

/**
 * @param {import('./schema.js').Schema} schema
 * @param {object} menu
 * @returns {{path: Array, message: string}[]}
 */
export function check(schema, menu, known = null) {
  const problems = []
  const walk = new Walker(schema, problems)
  walk.object(schema.root, menu, [])
  if (known) materials(menu, known, problems)
  // The rest looks at the menu as the editor holds it, with what is written the easy way written the one way.
  const normalized = normalize(schema, menu)
  slots(normalized, problems)
  bedrock(normalized, problems)
  return problems
}

class Walker {
  constructor(schema, problems) {
    this.schema = schema
    this.problems = problems
  }

  /** [fix] is the key a mistyped one probably is, which the form offers as a button. */
  add(path, message, fix = null) { this.problems.push(fix ? { path, message, fix } : { path, message }) }

  object(typeName, value, path, variant = false) {
    if (!isObject(value)) return this.add(path, 'should be an object')
    const fields = this.schema.fields(typeName)
    for (const field of fields) if (field.required && value[field.key] === undefined) this.add([...path, field.key], `'${field.key}' is required`)
    for (const key of Object.keys(value)) {
      const field = fields.find((f) => f.key === key)
      if (!field) {
        if (!(variant && key === 'type')) {
          const names = fields.map((f) => f.key).filter((k) => !(k in value))
          this.add([...path, key], `'${key}' is not a key of ${what(typeName)}, so it is ignored${hint(key, names)}`, closest(key, names))
        }
        continue
      }
      this.value(field.type, value[key], [...path, key], field)
    }
  }

  /** A number outside the limits the model gives it (of each entry, for a list): the same words as the plugin's. */
  range(field, value, path) {
    const number = numberOf(value, null)
    if (number === null || !field || (field.min === undefined && field.max === undefined)) return
    const { min, max } = field
    if ((min === undefined || number >= min) && (max === undefined || number <= max)) return
    this.add(path, `${min !== undefined && max !== undefined ? `should be from ${min} to ${max}` : min !== undefined ? `can't be below ${min}` : `can't be above ${max}`}, not ${value}`)
  }

  value(type, value, path, field = null) {
    if (value === null || value === undefined) return
    switch (type.kind) {
      case 'string':
        if (typeof value === 'object') this.add(path, 'should be text'); break
      case 'duration':
        if (typeof value === 'object' || !DURATION.test(String(value).trim())) this.add(path, 'should be a time such as 5s, 500ms or 1m'); break
      case 'int': case 'number':
        if (typeof value !== 'number' && numberOf(value, null) === null && !(typeof value === 'string' && value.includes('%'))) this.add(path, 'should be a number')
        else if (type.kind === 'int' && typeof value === 'number' && !Number.isInteger(value)) this.add(path, 'should be a whole number')
        else this.range(field, value, path)
        break
      case 'boolean': if (typeof value !== 'boolean' && value !== 'true' && value !== 'false') this.add(path, 'should be true or false'); break
      case 'dynamic': {
        // A number or a switch, or text with a placeholder that the plugin works out when it draws the item.
        const text = typeof value === 'string' && /[%{]/.test(value)
        if (type.of === 'boolean') { if (typeof value !== 'boolean' && !text && flagOf(value, null) === null) this.add(path, 'should be true or false, or a placeholder that gives one') }
        else if (typeof value === 'number') { if (!Number.isInteger(value)) this.add(path, 'should be a whole number') }
        else if (!text && numberOf(value, null) === null) this.add(path, 'should be a number, or a placeholder such as %player_level% that gives one')
        if (type.of === 'int') this.range(field, value, path)
        break
      }
      // The plugin reads an enum in any case.
      case 'enum': if (!type.values.some((v) => v.toLowerCase() === String(value).toLowerCase())) this.add(path, `'${value}' is not one of ${type.values.join(', ')}${hint(String(value), type.values)}`); break
      case 'list':
        // One entry may be written without the brackets (`slots = 5`).
        if (Array.isArray(value)) value.forEach((item, i) => this.value(type.of, item, [...path, i], field))
        else this.value(type.of, value, path, field)
        break
      case 'map':
        if (!isObject(value)) this.add(path, 'should be an object')
        else for (const key of Object.keys(value)) this.value(type.of, value[key], [...path, key])
        break
      case 'object': this.object(type.type, value, path); break
      case 'poly': {
        if (!isObject(value)) return this.add(path, 'should be an object')
        const name = value.type
        if (typeof name !== 'string') return this.add([...path, 'type'], "'type' is required")
        const typeName = this.schema.variantType(type.base, name)
        // A name with a colon is a type another plugin added, which the editor cannot know.
        if (!typeName) { if (!name.includes(':')) this.add([...path, 'type'], `'${name}' is not ${/^[aeiou]/i.test(type.base) ? 'an' : 'a'} ${type.base.toLowerCase()}${hint(name, this.schema.variants(type.base)) || ' (see the list in the README)'}`, closest(name, this.schema.variants(type.base))); return }
        this.object(typeName, value, path, true)
        break
      }
    }
  }
}

/** How a type reads in a sentence. */
function what(typeName) {
  return typeName === 'Menu' ? 'a menu' : typeName === 'Item' ? 'an item' : `'${typeName.split(':').pop()}'`
}

/** The times the plugin reads: `5s`, `1m 30s`, `500ms`, or the ISO form. */
const DURATION = /^(?:(?:\d+(?:\.\d+)?(?:ns|us|ms|s|m|h|d)\s*)+|[-+]?P.+|[-+]?Infinity)$/i

function isObject(value) { return typeof value === 'object' && value !== null && !Array.isArray(value) }

/** Slots outside the window, and a menu matrix that breaks its rules or names missing items. */
function slots(menu, problems) {
  const count = slotCount(menu)
  const chest = menu.type === undefined || menu.type === 'chest'
  const matrixByItem = new Map()
  if (isObject(menu.matrix)) {
    const cells = Array.isArray(menu.matrix.cells) ? menu.matrix.cells : []
    const map = isObject(menu.matrix.map) ? menu.matrix.map : {}
    cells.forEach((row, r) => {
      const chars = [...String(row ?? '')]
      if (chars.length > 9) problems.push({ path: ['matrix', 'cells', r], message: 'a row of matrix.cells has more than 9 characters' })
      chars.forEach((c, column) => {
        const target = map[c]
        if (typeof target === 'string') {
          if (!matrixByItem.has(target)) matrixByItem.set(target, [])
          matrixByItem.get(target).push(r * 9 + column)
        }
      })
    })
    if (chest && cells.length * 9 > count) {
      problems.push({ path: ['matrix', 'cells'], message: `matrix.cells has ${cells.length} rows, more than the menu` })
    }
    for (const [character, name] of Object.entries(map)) {
      if ([...character].length !== 1) problems.push({ path: ['matrix', 'map', character], message: `matrix.map names single characters, not '${character}'` })
      else if (!(name in (menu.items ?? {}))) problems.push({ path: ['matrix', 'map', character], message: `the item '${name}' is not in items` })
    }
  }
  for (const [name, item] of Object.entries(menu.items ?? {})) {
    const claimed = [item.slot, ...(item.slots ?? [])].filter((n) => typeof n === 'number')
    // A negative slot is told by the limits the model gives; here is what the size of this menu adds.
    for (const slot of claimed) if (slot >= count) problems.push({ path: ['items', name, 'slot'], message: `slot ${slot} is outside the menu (0 to ${count - 1}); the slots after it are the player's inventory, which an item of the menu draws only with 'inventory-slots'` })
    const rows = (item['inventory-slots'] ?? []).filter((n) => typeof n === 'number')
    const fromMatrix = matrixByItem.get(name) ?? []
    if (claimed.length === 0 && rows.length === 0 && fromMatrix.length === 0) problems.push({ path: ['items', name], message: 'has no slot, so it is never shown' })
  }
  if (chest) {
    if (count % 9 !== 0 || count < 9 || count > 54) problems.push({ path: ['size'], message: `size ${count} is not 9, 18, … 54` })
  }
}

/** Bedrock form checks: modal buttons, custom form fields, unique ids and slider/dropdown bounds. */
function bedrock(menu, problems) {
  const cfg = menu.bedrock
  if (!isObject(cfg)) return
  const mode = String(cfg.mode ?? 'AUTO').toUpperCase()
  if (mode === 'MODAL') {
    if (!isObject(cfg.confirm)) problems.push({ path: ['bedrock', 'confirm'], message: 'MODAL mode needs a confirm button' })
    if (!isObject(cfg.cancel)) problems.push({ path: ['bedrock', 'cancel'], message: 'MODAL mode needs a cancel button' })
  }
  const fields = Array.isArray(cfg.fields) ? cfg.fields : []
  if (mode === 'CUSTOM' && fields.length === 0) {
    problems.push({ path: ['bedrock', 'fields'], message: 'CUSTOM mode needs at least one field' })
  }
  const seen = new Set()
  fields.forEach((field, index) => {
    if (!isObject(field)) return
    const type = String(field.type ?? '').toLowerCase()
    const path = ['bedrock', 'fields', index]
    if (type !== 'label' && typeof field.id === 'string') {
      const id = field.id.trim()
      if (!id) problems.push({ path: [...path, 'id'], message: 'id cannot be empty' })
      else if (seen.has(id)) problems.push({ path: [...path, 'id'], message: `duplicate field id '${id}'` })
      else seen.add(id)
    }
    if (type === 'dropdown') {
      const options = Array.isArray(field.options) ? field.options : []
      if (options.length === 0) problems.push({ path: [...path, 'options'], message: 'dropdown needs at least one option' })
      else if (typeof field.default === 'number' && (field.default < 0 || field.default >= options.length)) {
        problems.push({ path: [...path, 'default'], message: `default ${field.default} is outside 0..${options.length - 1}` })
      }
    }
    if (type === 'step-slider') {
      const steps = Array.isArray(field.steps) ? field.steps : []
      if (steps.length === 0) problems.push({ path: [...path, 'steps'], message: 'step-slider needs at least one step' })
      else if (typeof field.default === 'number' && (field.default < 0 || field.default >= steps.length)) {
        problems.push({ path: [...path, 'default'], message: `default ${field.default} is outside 0..${steps.length - 1}` })
      }
    }
    if (type === 'slider') {
      const min = typeof field.min === 'number' ? field.min : 0
      const max = typeof field.max === 'number' ? field.max : 100
      const step = typeof field.step === 'number' ? field.step : 1
      if (max <= min) problems.push({ path: [...path, 'max'], message: `max (${max}) must be greater than min (${min})` })
      if (step <= 0) problems.push({ path: [...path, 'step'], message: `step (${step}) must be positive` })
      if (typeof field.default === 'number' && (field.default < min || field.default > max)) {
        problems.push({ path: [...path, 'default'], message: `default (${field.default}) must be between ${min} and ${max}` })
      }
    }
  })
}

/**
 * Items whose material is not an item that [known] (see `knownItems`) lists: a key of the game that does not exist, or an item that the server's plugin of that source does not have,
 * with the one it was probably meant to be, as the plugin says it. A source the editor has no list of (heads, another plugin's items) and text with a placeholder are left alone.
 */
function materials(menu, known, problems) {
  for (const [id, item] of Object.entries(menu.items ?? {})) {
    const text = typeof item?.material === 'string' ? item.material.trim() : ''
    if (!text || /[%{]/.test(text)) continue
    const colon = text.indexOf(':')
    const prefix = colon < 0 ? '' : text.slice(0, colon).toLowerCase()
    const name = colon < 0 ? text : text.slice(colon + 1)
    if (name.includes(':')) continue
    let pool, what, word
    if (prefix === '' || prefix === 'minecraft') { pool = known.vanilla; what = 'an item of the game'; word = name.toLowerCase() }
    else if (known.custom.has(prefix)) { pool = known.custom.get(prefix); what = `an item of ${prefix}`; word = name }
    else continue
    if (!pool.has(word)) problems.push({ path: ['items', id, 'material'], message: `'${text}' isn't ${what}${hint(word, [...pool])}` })
  }
}
