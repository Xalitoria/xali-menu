// A menu file may write a few things the easy way, and the plugin reads them as the one way: a single value where a list is expected (`slots = 5`), an enum in
// any case (`rarity = epic`), a number or a switch as text (`slot = "3"`). The editor works with the one way only, so a menu is put into it as it comes in.

/**
 * @param {import('./schema.js').Schema} schema
 * @param {*} menu
 * @returns {*} a copy of [menu] with the easy forms written the one way
 */
export function normalize(schema, menu) {
  return walk(schema, { kind: 'object', type: schema.root }, menu)
}

function walk(schema, type, value) {
  if (value === null || value === undefined) return value
  switch (type.kind) {
    case 'list': return (Array.isArray(value) ? value : [value]).map((item) => walk(schema, type.of, item))
    case 'map': return isObject(value) ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, walk(schema, type.of, v)])) : value
    case 'object': return isObject(value) ? fields(schema, type.type, value) : value
    case 'poly': {
      const typeName = isObject(value) ? schema.variantType(type.base, value.type) : null
      return typeName ? fields(schema, typeName, value) : value
    }
    case 'enum': {
      const found = typeof value === 'string' ? type.values.find((v) => v.toLowerCase() === value.toLowerCase()) : undefined
      return found ?? value
    }
    case 'int': return typeof value === 'string' && /^-?\d+$/.test(value.trim()) ? Number(value) : value
    case 'number': return typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value)) ? Number(value) : value
    case 'boolean': return value === 'true' ? true : value === 'false' ? false : value
    default: return value
  }
}

function fields(schema, typeName, value) {
  const known = new Map(schema.fields(typeName).map((f) => [f.key, f]))
  return Object.fromEntries(Object.entries(value).map(([key, v]) => [key, known.has(key) ? walk(schema, known.get(key).type, v) : v]))
}

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)
