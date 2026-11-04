import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parse, HoconError } from '../src/hocon.js'
import { write } from '../src/hoconWriter.js'

test('reads keys, dotted keys and nested objects', () => {
  const value = parse('title = "T"\nsize: 27\nitems {\n  a { material = stone, slot = 0, click.any.actions = [ { type = close } ] }\n}\n')
  assert.deepEqual(value, { title: 'T', size: 27, items: { a: { material: 'stone', slot: 0, click: { any: { actions: [{ type: 'close' }] } } } } })
})

test('reads unquoted words, numbers, booleans, null and durations', () => {
  const value = parse('a = hello\nb = 1.5\nc = true\nd = null\ne = 20s\nf = minecraft:stone\ng = -3')
  assert.deepEqual(value, { a: 'hello', b: 1.5, c: true, d: null, e: '20s', f: 'minecraft:stone', g: -3 })
})

test('reads lists split by commas or lines, with comments', () => {
  const value = parse('a = [1, 2\n 3] # three\n// full line\nb = [\n  "x"\n  "y",\n]')
  assert.deepEqual(value, { a: [1, 2, 3], b: ['x', 'y'] })
})

test('merges objects with the same key and appends with +=', () => {
  const value = parse('a { x = 1 }\na { y = 2 }\nl = [1]\nl += 2\nb.c = 1\nb.d = 2')
  assert.deepEqual(value, { a: { x: 1, y: 2 }, l: [1, 2], b: { c: 1, d: 2 } })
})

test('reads escapes, quoted keys and multi-line strings', () => {
  const value = parse('a = "x\\ny\\u0041\\"q\\""\n"b.c" = 1\nd = """two\nlines"""')
  assert.deepEqual(value, { a: 'x\nyA"q"', 'b.c': 1, d: 'two\nlines' })
})

test('keeps # inside quotes and a // inside a url-like quoted string', () => {
  assert.deepEqual(parse('a = "#ff0000"\nb = "http://x"'), { a: '#ff0000', b: 'http://x' })
})

test('reports where a mistake is, and refuses substitutions', () => {
  assert.throws(() => parse('a = {\n b = 1\n'), (e) => e instanceof HoconError && /missing '}'/.test(e.message))
  assert.throws(() => parse('a = ${b}'), /substitutions/)
  assert.throws(() => parse('a = "open\n'), /unclosed/)
  assert.throws(() => parse('a b'), /expected '='/)
})

test('writes short objects on one line and long ones spread out', () => {
  const text = write({ title: 'T', items: { a: { material: 'stone', slot: 0 } }, commands: ['x', 'y'] })
  assert.equal(text, 'title = "T"\ncommands = ["x", "y"]\nitems = { a = { material = "stone", slot = 0 } }\n')
  const long = write({ items: { a: { material: 'stone', note: 'x'.repeat(120) } } })
  assert.match(long, /^items \{\n    a \{\n        material = "stone"\n        note = /)
})

test('what is written reads back the same', () => {
  const original = {
    title: '&6Shop', size: 54, 'weird key': { x: [1, 2.5, true, null] },
    items: { a: { material: 'stone', slot: 0, click: { left: { actions: [{ type: 'message', text: 'a '.repeat(60) }, { type: 'close' }] } } } },
  }
  assert.deepEqual(parse(write(original)), original)
})
