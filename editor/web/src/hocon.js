// Reads the subset of HOCON that menu files use, into plain objects, arrays, strings, numbers, booleans and null.
//
// Supported: `key = value`, `key: value`, `key { ... }`, dotted keys (`click.left.actions = [...]`), `+=` on lists,
// quoted and """multi-line""" strings, unquoted strings and numbers, lists and objects separated by commas or lines,
// and `#` and `//` comments. Objects with the same key are merged, as HOCON does.
// Not supported: substitutions (`${x}`), `include`, and value concatenation across lines. These raise an error.

export class HoconError extends Error {
  constructor(message, line, column) {
    super(`line ${line}, column ${column}: ${message}`)
    this.line = line
    this.column = column
  }
}

const NUMBER = /^-?\d+(\.\d+)?([eE][+-]?\d+)?$/

/** @param {string} text @returns {any} */
export function parse(text) {
  return new Reader(text).root()
}

class Reader {
  constructor(text) {
    this.text = text.replace(/^﻿/, '')
    this.at = 0
  }

  fail(message) {
    const before = this.text.slice(0, this.at)
    const line = before.split('\n').length
    throw new HoconError(message, line, this.at - before.lastIndexOf('\n'))
  }

  peek(offset = 0) { return this.text[this.at + offset] }

  /** Skips spaces and comments; also line breaks and commas when [separators] is true. */
  skip(separators) {
    for (;;) {
      const c = this.peek()
      if (c === ' ' || c === '\t' || c === '\r') this.at++
      else if (separators && (c === '\n' || c === ',')) this.at++
      else if (c === '#' || (c === '/' && this.peek(1) === '/')) {
        while (this.at < this.text.length && this.peek() !== '\n') this.at++
      } else return
    }
  }

  root() {
    this.skip(true)
    let result
    if (this.peek() === '{') {
      result = this.object()
      this.skip(true)
    } else {
      result = this.members(null)
    }
    if (this.at < this.text.length) this.fail(`unexpected '${this.peek()}'`)
    return result
  }

  /** The members of an object up to [close] (null for the end of the text). */
  members(close) {
    const result = {}
    for (;;) {
      this.skip(true)
      const c = this.peek()
      if (c === undefined) { if (close) this.fail("missing '}'"); return result }
      if (c === close) return result
      const path = this.path()
      this.skip(false)
      let append = false
      const sep = this.peek()
      if (sep === '+' && this.peek(1) === '=') { append = true; this.at += 2 }
      else if (sep === '=' || sep === ':') this.at++
      else if (sep !== '{') this.fail(`expected '=' or '{' after '${path.join('.')}'`)
      this.skip(true)
      const value = this.value()
      assign(result, path, value, append, (m) => this.fail(m))
    }
  }

  /** A key: unquoted and quoted parts joined by dots. */
  path() {
    const parts = []
    let current = ''
    let quoted = false
    for (;;) {
      const c = this.peek()
      if (c === '"') { current += this.string(); quoted = true }
      else if (c === '.') { parts.push(current); current = ''; quoted = false; this.at++ }
      else if (c === undefined || /[\s=:{}\[\],+#]/.test(c) || (c === '/' && this.peek(1) === '/')) break
      else { current += c; this.at++ }
    }
    if (current === '' && !quoted && parts.length === 0) this.fail('expected a key')
    parts.push(current)
    return parts
  }

  value() {
    const c = this.peek()
    if (c === '{') return this.object()
    if (c === '[') return this.list()
    if (c === '"') {
      if (this.text.startsWith('"""', this.at)) return this.multiline()
      return this.string()
    }
    if (c === '$') this.fail('substitutions (${...}) are not supported')
    if (c === undefined || c === '\n' || c === ',' || c === '}' || c === ']') this.fail('expected a value')
    let end = this.at
    while (end < this.text.length) {
      const d = this.text[end]
      if (d === '\n' || d === ',' || d === '}' || d === ']' || d === '#' || (d === '/' && this.text[end + 1] === '/')) break
      if (d === '$') { this.at = end; this.fail('substitutions (${...}) are not supported') }
      end++
    }
    const word = this.text.slice(this.at, end).trim()
    this.at = end
    if (word === 'true') return true
    if (word === 'false') return false
    if (word === 'null') return null
    if (NUMBER.test(word)) return Number(word)
    return word
  }

  object() {
    this.at++ // {
    const result = this.members('}')
    this.at++ // }
    return result
  }

  list() {
    this.at++ // [
    const items = []
    for (;;) {
      this.skip(true)
      const c = this.peek()
      if (c === undefined) this.fail("missing ']'")
      if (c === ']') { this.at++; return items }
      items.push(this.value())
    }
  }

  string() {
    this.at++ // opening quote
    let out = ''
    for (;;) {
      const c = this.peek()
      if (c === undefined || c === '\n') this.fail('unclosed string')
      this.at++
      if (c === '"') return out
      if (c !== '\\') { out += c; continue }
      const e = this.peek()
      this.at++
      switch (e) {
        case 'n': out += '\n'; break
        case 't': out += '\t'; break
        case 'r': out += '\r'; break
        case 'b': out += '\b'; break
        case 'f': out += '\f'; break
        case 'u': {
          const hex = this.text.slice(this.at, this.at + 4)
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) this.fail('bad \\u escape')
          out += String.fromCharCode(parseInt(hex, 16))
          this.at += 4
          break
        }
        case '"': case '\\': case '/': out += e; break
        default: this.fail(`unknown escape '\\${e}'`)
      }
    }
  }

  multiline() {
    this.at += 3
    const end = this.text.indexOf('"""', this.at)
    if (end < 0) this.fail('unclosed """ string')
    let stop = end
    while (this.text[stop + 3] === '"') stop++ // a run of quotes at the end belongs to the string
    const value = this.text.slice(this.at, stop)
    this.at = stop + 3
    return value
  }
}

/** Sets [path] in [target] to [value], merging objects that meet, and appending to a list for `+=`. */
function assign(target, path, value, append, fail) {
  let node = target
  for (let i = 0; i < path.length - 1; i++) {
    const key = path[i]
    if (typeof node[key] !== 'object' || node[key] === null || Array.isArray(node[key])) node[key] = {}
    node = node[key]
  }
  const last = path[path.length - 1]
  if (append) {
    if (node[last] === undefined) node[last] = []
    if (!Array.isArray(node[last])) fail(`'${path.join('.')}' is not a list, so += cannot add to it`)
    node[last].push(value)
  } else if (isObject(node[last]) && isObject(value)) {
    merge(node[last], value)
  } else {
    node[last] = value
  }
}

function isObject(value) { return typeof value === 'object' && value !== null && !Array.isArray(value) }

function merge(into, from) {
  for (const key of Object.keys(from)) {
    if (isObject(into[key]) && isObject(from[key])) merge(into[key], from[key])
    else into[key] = from[key]
  }
}
