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
export function check(schema, menu) {
  const problems = []
  const walk = new Walker(schema, problems)
  walk.object(schema.root, menu, [])
  // The rest looks at the menu as the editor holds it, with what is written the easy way written the one way.
  slots(normalize(schema, menu), problems)
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

/** Slots outside the window, and layouts that name items that are not there. */
function slots(menu, problems) {
  const count = slotCount(menu)
  for (const [name, item] of Object.entries(menu.items ?? {})) {
    const claimed = [item.slot, ...(item.slots ?? [])].filter((n) => typeof n === 'number')
    // A negative slot is told by the limits the model gives; here is what the size of this menu adds.
    for (const slot of claimed) if (slot >= count) problems.push({ path: ['items', name, 'slot'], message: `slot ${slot} is outside the menu (0 to ${count - 1}); the slots after it are the player's inventory, which an item of the menu draws only with 'inventory-slots'` })
    const rows = (item['inventory-slots'] ?? []).filter((n) => typeof n === 'number')
    if (claimed.length === 0 && rows.length === 0 && !Object.values(menu.legend ?? {}).includes(name)) problems.push({ path: ['items', name], message: 'has no slot, so it is never shown' })
  }
  for (const [character, name] of Object.entries(menu.legend ?? {})) {
    if (!(name in (menu.items ?? {}))) problems.push({ path: ['legend', character], message: `the item '${name}' is not in items` })
  }
  if (menu.type === undefined || menu.type === 'chest') {
    if (count % 9 !== 0 || count < 9 || count > 54) problems.push({ path: ['size'], message: `size ${count} is not 9, 18, … 54` })
  }
}
