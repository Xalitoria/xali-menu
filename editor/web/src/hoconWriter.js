// Writes plain objects as HOCON in the style the plugin uses for the menus it writes (see HoconWriter.kt): short plain
// objects and lists stay on one line, longer ones are spread out, an object that holds other objects (the items of a menu,
// the inventory) always is, and the keys that name things come first.

const WIDTH = 100
const INDENT = '    '
const FIRST = ['type', 'title', 'size', 'material', 'slot', 'slots', 'name', 'command']
const BARE = /^[A-Za-z_][A-Za-z0-9_-]*$/

/** @param {object} root @returns {string} */
export function write(root) {
  return body(root, '')
}

function keys(object) {
  const rank = (key) => { const i = FIRST.indexOf(key); return i < 0 ? FIRST.length : i }
  return Object.keys(object).sort((a, b) => rank(a) - rank(b) || (a < b ? -1 : a > b ? 1 : 0))
}

function label(key) {
  return BARE.test(key) ? key : quote(key)
}

function quote(text) {
  return JSON.stringify(text)
}

/** Whether [value] has an object (that isn't empty) among its values: a map of things, not one thing. */
function holdsObjects(value) {
  return Object.values(value).some((child) => isObject(child) && Object.keys(child).length > 0)
}

function isObject(value) { return typeof value === 'object' && value !== null && !Array.isArray(value) }

function body(object, indent) {
  let out = ''
  for (const key of keys(object)) {
    const value = object[key]
    if (isObject(value) && (holdsObjects(value) || inline(value) === null)) {
      out += `${indent}${label(key)} {\n${body(value, indent + INDENT)}${indent}}\n`
    } else {
      out += `${indent}${label(key)} = ${render(value, indent)}\n`
    }
  }
  return out
}

function render(value, indent) {
  const flat = inline(value)
  if (flat !== null) return flat
  if (Array.isArray(value) && value.length > 0 && value.every((item) => typeof item === 'number')) {
    // A long list of numbers (the slots of a menu) is filled in lines, not one number to a line.
    const lines = []
    let line = ''
    for (const item of value) {
      const piece = String(item)
      if (line !== '' && (indent + INDENT + line + ', ' + piece + ',').length > WIDTH) { lines.push(`${line},`); line = piece } else line = line === '' ? piece : `${line}, ${piece}`
    }
    lines.push(line)
    return `[\n${lines.map((l) => `${indent + INDENT}${l}\n`).join('')}${indent}]`
  }
  if (Array.isArray(value)) {
    return `[\n${value.map((item) => `${indent + INDENT}${render(item, indent + INDENT)}\n`).join('')}${indent}]`
  }
  return `{\n${body(value, indent + INDENT)}${indent}}`
}

/** [value] on one line, or null if it has to span several. */
function inline(value) {
  let text
  if (value === null) text = 'null'
  else if (typeof value === 'string') text = quote(value)
  else if (Array.isArray(value)) {
    const items = value.map(inline)
    if (items.includes(null)) return null
    text = `[${items.join(', ')}]`
    return text.length <= WIDTH ? text : null
  } else if (isObject(value)) {
    const entries = keys(value).map((key) => { const flat = inline(value[key]); return flat === null ? null : `${label(key)} = ${flat}` })
    if (entries.includes(null)) return null
    text = entries.length === 0 ? '{}' : `{ ${entries.join(', ')} }`
    return text.length <= WIDTH ? text : null
  } else text = String(value)
  return text
}
