// Writes REFERENCE.md, the page that lists every key of a menu file, from schema.json. It has no DOM and no state, so a test can
// ask for the text and compare it with the file: a change to the model that is not followed by `node editor/tools/reference.mjs`
// fails there.

const BASES = [
  ['Action', 'Actions', 'What a click, an open or a close does: one entry of a list, with `type` naming the action.'],
  ['Condition', 'Conditions', 'A check that is true or false: one entry of a `require` list, with `type` naming the condition.'],
  ['Activator', 'Activators', 'What opens a menu besides its commands, with `type` naming the activator.'],
  ['Catalog', 'Catalogs', 'A list of things a menu draws from, such as the players or the menus, with `type` naming the catalog.'],
  ['BedrockField', 'Bedrock form fields', 'An interactive control or label of a Bedrock `CUSTOM` form, with `type` naming the field.'],
]

const baseTitle = (base) => BASES.find(([name]) => name === base)?.[1] ?? base

/** The anchor GitHub gives a heading. */
export function slug(heading) {
  return heading.toLowerCase().replace(/[`]/g, '').replace(/[^a-z0-9 _-]/g, '').trim().replace(/ /g, '-')
}

const typeHeading = (name) => name
const memberHeading = (base, key) => `${base} \`${key}\``
const link = (text, heading) => `[${text}](#${slug(heading)})`

/** A type of a field in words, with a link where it is described below. */
export function describe(type) {
  switch (type.kind) {
    case 'string': return 'text'
    case 'int': return 'whole number'
    case 'number': return 'number'
    case 'boolean': return '`true` or `false`'
    case 'duration': return 'time, such as `5s`, `500ms` or `1m`'
    case 'enum': return `one of ${type.values.map((v) => `\`${v}\``).join(', ')}`
    case 'object': return link(type.type, typeHeading(type.type))
    case 'poly': return `${link(`${/^[aeiou]/i.test(type.base) ? 'an' : 'a'} ${type.base.toLowerCase()}`, baseTitle(type.base))}`
    case 'list': return `list of ${describe(type.of)}`
    case 'map': return `map from a name to ${describe(type.of)}`
    case 'dynamic': return type.of === 'boolean'
      ? '`true` or `false`, or text with placeholders that works out to one'
      : 'whole number, or text with placeholders that works out to one'
    default: return type.kind
  }
}

function range(field) {
  const { min, max } = field
  if (min != null && max != null) return `${min} to ${max}`
  if (min != null) return `at least ${min}`
  if (max != null) return `at most ${max}`
  return null
}

function value(v) {
  if (Array.isArray(v)) return v.length ? `[${v.map(value).join(', ')}]` : '`[]`'
  return `\`${typeof v === 'string' ? JSON.stringify(v) : v}\``
}

/** Doc text from the model's comments, set under a bullet: later lines are indented to stay in it. */
function indent(doc) {
  return doc.trim().split('\n').map((line) => (line ? `  ${line}` : '')).join('\n')
}

function fieldLines(field) {
  const facts = [describe(field.type)]
  if (field.required) facts.push('required')
  else if (field.default !== undefined) facts.push(`default ${value(field.default)}`)
  const limits = range(field)
  if (limits) facts.push(limits)
  const head = `- \`${field.key}\`: ${facts.join('; ')}`
  return field.doc ? `${head}\n\n${indent(field.doc)}\n` : `${head}\n`
}

function fieldsOf(type) {
  return type.fields.length ? type.fields.map(fieldLines).join('\n') : 'It has no keys of its own.\n'
}

/** @param {{version: string, root: string, types: Object, poly: Object}} schema */
export function reference(schema) {
  const out = []
  const names = Object.keys(schema.types)
  const plain = names.filter((n) => !n.includes(':'))
  const rootFirst = [schema.root, ...plain.filter((n) => n !== schema.root)]

  out.push(`# Menu file reference

Every key of a menu file, XaliMenu ${schema.version}. This page is written by \`node editor/tools/reference.mjs\` from \`editor/web/schema.json\`,
which comes from the plugin's classes and their comments, so it lists what the plugin reads. Do not edit it by hand: change the comment on
the key, then run \`./gradlew :menu:editor:schema\` and the command above.

A file is HOCON, \`menus/<name>.conf\`, with keys in kebab-case (\`max-stack-size\`) and enum values in upper case (\`SLOT\`).
The plugin warns about a key it does not read and names the one it thinks you meant; see "What the plugin says about a file" in the [README](README.md).
A value that is a list may be written as the one value, and an enum in any case is read.

## Contents
`)
  out.push(rootFirst.map((n) => `- ${link(n, typeHeading(n))}`).join('\n'))
  out.push(BASES.map(([base, title]) => `- ${link(title, title)}`).join('\n'))
  out.push('')

  for (const name of rootFirst) {
    const type = schema.types[name]
    out.push(`## ${typeHeading(name)}\n`)
    if (type.doc) out.push(`${type.doc.trim()}\n`)
    out.push(fieldsOf(type))
  }

  for (const [base, title, about] of BASES) {
    const members = Object.entries(schema.poly[base] ?? {}).sort(([a], [b]) => a.localeCompare(b))
    if (!members.length) continue
    out.push(`## ${title}\n`)
    out.push(`${about}\n`)
    out.push(members.map(([key]) => link(`\`${key}\``, memberHeading(base, key))).join(', ') + '\n')
    for (const [key, typeName] of members) {
      const type = schema.types[typeName]
      out.push(`### ${memberHeading(base, key)}\n`)
      if (type.doc) out.push(`${type.doc.trim()}\n`)
      out.push(fieldsOf(type))
    }
  }

  return out.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd() + '\n'
}
