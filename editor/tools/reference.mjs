// Writes ../../REFERENCE.md from ../web/schema.json. Run it after `./gradlew :menu:editor:schema`.
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { reference } from '../web/src/reference.js'

const here = (path) => fileURLToPath(new URL(path, import.meta.url))
const schema = JSON.parse(readFileSync(here('../web/schema.json'), 'utf8'))
writeFileSync(here('../../REFERENCE.md'), reference(schema))
console.log('REFERENCE.md written')
