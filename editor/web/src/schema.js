// Reads schema.json (written by `./gradlew :menu:editor:schema` from the plugin's classes) and answers questions about it.

export class Schema {
  /** @param {{root: string, types: Object, poly: Object, version: string}} data */
  constructor(data) {
    this.data = data
    this.version = data.version
    this.root = data.root
  }

  static async load(url = 'schema.json') {
    const response = await fetch(url)
    if (!response.ok) throw new Error(`The schema could not be loaded (${response.status})`)
    return new Schema(await response.json())
  }

  /** @returns {{fields: Array, doc?: string}} */
  type(name) {
    const type = this.data.types[name]
    if (!type) throw new Error(`The schema has no type '${name}'`)
    return type
  }

  fields(typeName) { return this.type(typeName).fields }

  field(typeName, key) { return this.fields(typeName).find((f) => f.key === key) }

  /** The names of the types of a base (`Action`...), sorted. */
  variants(base) { return Object.keys(this.data.poly[base] ?? {}).sort() }

  /** The name in `types` of the variant [name] of [base], or null if there is none (a type another plugin added). */
  variantType(base, name) { return this.data.poly[base]?.[name] ?? null }

  /** The type name to show the fields of [value], an object of the polymorphic [base]. */
  typeOfValue(base, value) { return this.variantType(base, value?.type) }

  /** A value to put in when something is added: the defaults a type needs to be valid. */
  blank(type) {
    switch (type.kind) {
      case 'string': case 'duration': return ''
      case 'int': case 'number': return 0
      case 'boolean': return false
      case 'enum': return type.values[0]
      case 'list': return []
      case 'map': return {}
      case 'poly': return { type: this.variants(type.base)[0] }
      case 'object': return this.blankObject(type.type)
      default: return null
    }
  }

  /** An object of [typeName] with its required keys filled. */
  blankObject(typeName) {
    const result = {}
    for (const field of this.fields(typeName)) if (field.required) result[field.key] = this.blank(field.type)
    return result
  }

  /** A blank object of the variant [name] of [base]. */
  blankVariant(base, name) {
    const typeName = this.variantType(base, name)
    return { type: name, ...(typeName ? this.blankObject(typeName) : {}) }
  }
}

/** A short text for a value in a collapsed row. */
export function summary(value) {
  if (value === null || value === undefined) return ''
  if (typeof value !== 'object') return String(value)
  if (Array.isArray(value)) return `${value.length} ${value.length === 1 ? 'entry' : 'entries'}`
  const pick = ['type', 'material', 'text', 'name', 'node', 'menu', 'sound', 'command', 'title'].find((k) => typeof value[k] === 'string')
  return pick ? (pick === 'type' ? value.type : `${value.type ? value.type + ': ' : ''}${value[pick]}`) : `${Object.keys(value).length} ${Object.keys(value).length === 1 ? 'key' : 'keys'}`
}
