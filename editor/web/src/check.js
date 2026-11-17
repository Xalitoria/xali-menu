// Finds what is wrong with a menu without asking the server: a missing required key, a key the format does not have, a value of
// the wrong kind, and a slot that is outside the window. The plugin does the same and more when it reads the file, and the
// editor shows what it says too; this is for the problems that can be shown while typing.

import { numberOf, flagOf } from './dynamic.js'

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
  slots(menu, problems)
  return problems
}

class Walker {
  constructor(schema, problems) {
    this.schema = schema
    this.problems = problems
  }

  add(path, message) { this.problems.push({ path, message }) }

  object(typeName, value, path, variant = false) {
    if (!isObject(value)) return this.add(path, 'should be an object')
    const fields = this.schema.fields(typeName)
    for (const field of fields) if (field.required && value[field.key] === undefined) this.add([...path, field.key], `'${field.key}' is required`)
    for (const key of Object.keys(value)) {
      const field = fields.find((f) => f.key === key)
      if (!field) { if (!(variant && key === 'type')) this.add([...path, key], `'${key}' is not a key of this`); continue }
      this.value(field.type, value[key], [...path, key])
    }
  }

  value(type, value, path) {
    if (value === null || value === undefined) return
    switch (type.kind) {
      case 'string': case 'duration':
        if (typeof value === 'object') this.add(path, 'should be text'); break
      case 'int': case 'number':
        if (typeof value !== 'number' && !(typeof value === 'string' && value.includes('%'))) this.add(path, 'should be a number')
        else if (type.kind === 'int' && typeof value === 'number' && !Number.isInteger(value)) this.add(path, 'should be a whole number')
        break
      case 'boolean': if (typeof value !== 'boolean') this.add(path, 'should be true or false'); break
      case 'dynamic': {
        // A number or a switch, or text with a placeholder that the plugin works out when it draws the item.
        const text = typeof value === 'string' && /[%{]/.test(value)
        if (type.of === 'boolean') { if (typeof value !== 'boolean' && !text && flagOf(value, null) === null) this.add(path, 'should be true or false, or a placeholder that gives one') }
        else if (typeof value === 'number') { if (!Number.isInteger(value)) this.add(path, 'should be a whole number') }
        else if (!text && numberOf(value, null) === null) this.add(path, 'should be a number, or a placeholder such as %player_level% that gives one')
        break
      }
      case 'enum': if (!type.values.includes(value)) this.add(path, `should be one of ${type.values.join(', ')}`); break
      case 'list':
        if (!Array.isArray(value)) this.add(path, 'should be a list')
        else value.forEach((item, i) => this.value(type.of, item, [...path, i]))
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
        if (!typeName) { if (!name.includes(':')) this.add([...path, 'type'], `'${name}' is not a known ${type.base.toLowerCase()}`); return }
        this.object(typeName, value, path, true)
        break
      }
    }
  }
}

function isObject(value) { return typeof value === 'object' && value !== null && !Array.isArray(value) }

/** Slots outside the window, and layouts that name items that are not there. */
function slots(menu, problems) {
  const count = slotCount(menu)
  for (const [name, item] of Object.entries(menu.items ?? {})) {
    const claimed = [item.slot, ...(item.slots ?? [])].filter((n) => typeof n === 'number')
    for (const slot of claimed) if (slot < 0 || slot >= count) problems.push({ path: ['items', name, 'slot'], message: `slot ${slot} is outside the menu (0 to ${count - 1}); the slots after it are the player's inventory, which an item of the menu draws only with 'inventory-slots'` })
    const rows = (item['inventory-slots'] ?? []).filter((n) => typeof n === 'number')
    for (const slot of rows) if (slot < 0 || slot > 35) problems.push({ path: ['items', name, 'inventory-slots'], message: `inventory slot ${slot} is outside the player's inventory (0 to 35: the hotbar, then the three rows above it)` })
    if (claimed.length === 0 && rows.length === 0 && !Object.values(menu.legend ?? {}).includes(name)) problems.push({ path: ['items', name], message: 'has no slot, so it is never shown' })
  }
  for (const [character, name] of Object.entries(menu.legend ?? {})) {
    if (!(name in (menu.items ?? {}))) problems.push({ path: ['legend', character], message: `the item '${name}' is not in items` })
  }
  if (menu.type === undefined || menu.type === 'chest') {
    if (count % 9 !== 0 || count < 9 || count > 54) problems.push({ path: ['size'], message: `size ${count} is not 9, 18, … 54` })
  }
}
