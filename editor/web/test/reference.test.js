import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { reference, describe, slug } from '../src/reference.js'

const schema = JSON.parse(readFileSync(new URL('../schema.json', import.meta.url), 'utf8'))

test('REFERENCE.md is what the schema says (run `node editor/tools/reference.mjs` after the schema task)', () => {
  const file = readFileSync(new URL('../../../REFERENCE.md', import.meta.url), 'utf8')
  assert.equal(file, reference(schema))
})

test('every type, action, condition, activator and catalog has a section, and every link of the page goes to a heading', () => {
  const text = reference(schema)
  for (const name of Object.keys(schema.types)) {
    const [base, key] = name.split(':')
    assert.ok(text.includes(`\n${key ? `### ${base} \`${key}\`` : `## ${name}`}\n`), `${name} has no section`)
  }
  const headings = new Set([...text.matchAll(/^#{2,3} (.+)$/gm)].map((m) => slug(m[1])))
  assert.deepEqual([...new Set([...text.matchAll(/\]\(#([^)]+)\)/g)].map((m) => m[1]).filter((a) => !headings.has(a)))], [])
})

test('a key says its type, whether it is required, its default and its range', () => {
  const text = reference(schema)
  assert.match(text, /- `amount`: whole number, or text with placeholders that works out to one; default `1`; 1 to 99/)
  assert.match(text, /- `title`: text; required/)
  assert.equal(describe({ kind: 'list', of: { kind: 'int' } }), 'list of whole number')
  assert.equal(describe({ kind: 'enum', values: ['A', 'B'] }), 'one of `A`, `B`')
})
