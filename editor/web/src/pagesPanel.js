// The panel of the "Pages" group: turning a menu into a paged one, choosing its catalog, the slots of its elements (picked in the window), and the
// values the item template can use. The template itself is an entry of the list of items, edited like any item.

import { h, doc } from './dom.js'
import { slotCount } from './check.js'
import { defaultPages, interiorSlots, listedSlots, TEMPLATE } from './pages.js'
import { geometry } from './window.js'

/** The first sentence of a text of the schema, for a line of help. */
const firstSentence = (text) => (text ?? '').split(/(?<=\.)\s/)[0]

/** The values that the template of a paged menu can use, as chips that copy `{name}`: those of the catalog, `index`, and the numbers of the page. */
export function valuesBar(app) {
  const own = Object.entries(app.catalogFields())
  const chip = (name, example, extra = '') => h('button', { class: `chip ${extra}`, title: example !== undefined ? `For example ${example}. Click to copy {${name}}.` : `Click to copy {${name}}.`,
    onclick: () => navigator.clipboard?.writeText(`{${name}}`) }, `{${name}}`, example !== undefined && h('small', null, example))
  return h('div', { class: 'values-bar' },
    h('div', { class: 'values-head' }, h('b', null, 'Values of an element'), h('small', { class: 'muted' }, 'Write these in the name, lore, material and actions.')),
    h('div', { class: 'chips' },
      own.map(([name, example]) => chip(name, example)),
      chip('index', '1'),
      own.length === 0 && h('small', { class: 'muted' }, 'This catalog does not say what its elements hold.')),
    h('div', { class: 'chips weak' }, ['page', 'pages', 'total', 'page_next', 'page_prev'].map((name) => chip(name, undefined, 'weak'))))
}

/** The content of the panel. */
export function pagesView(app) {
  const menu = app.store.value
  const pages = menu.pages
  if (!pages) {
    const count = slotCount(menu)
    return h('div', { class: 'pages-panel' },
      h('p', null, 'A paged menu lists the elements of a catalog (the players online, the worlds, a list of your own) a page at a time, drawing the same item for each.'),
      h('div', { class: 'row-buttons' },
        h('button', { class: 'primary', onclick: () => { app.store.set(['pages'], defaultPages(count, geometry(menu).columns)); app.picked.item = TEMPLATE; } }, 'Make this a paged menu')),
      h('p', { class: 'muted' }, 'It starts with the players online in the slots between the border. Change the catalog and the slots here, then edit the item in the list.'))
  }
  const { schema, form } = app
  const type = pages.catalog?.type
  const typeName = schema.variantType('Catalog', type)
  const known = app.catalogTypes()
  const select = h('select', { 'aria-label': 'Catalog', onchange: () => app.store.set(['pages', 'catalog'], schema.blankVariant('Catalog', select.value)) },
    type === undefined && h('option', { value: '' }, 'choose…'),
    type !== undefined && !known.includes(type) && h('option', { value: type, selected: true }, `${type} (unknown)`),
    known.map((n) => h('option', { value: n, selected: n === type }, n)))
  const help = typeName ? firstSentence(schema.type(typeName).doc) : null
  const slots = listedSlots(pages)
  const usesLayout = Boolean(pages.layout?.length)
  const columns = geometry(menu).columns
  const pagesType = schema.field(schema.root, 'pages').type.type
  return h('div', { class: 'pages-panel' },
    h('section', null,
      h('h4', null, 'Catalog'),
      h('p', { class: 'muted' }, 'Where the elements come from.'),
      select,
      help && h('div', { class: 'hint' }, doc(help)),
      typeName && form.object(typeName, ['pages', 'catalog'], { variant: true }),
      !typeName && type !== undefined && form.json(['pages', 'catalog'], pages.catalog, ['type'])),
    h('section', null,
      h('h4', null, 'Slots'),
      h('p', { class: 'muted' }, usesLayout
        ? `The elements go into the ${slots.length} cells of the layout of the pages. Pick the slots in the window to change them.`
        : `The elements go into ${slots.length} ${slots.length === 1 ? 'slot' : 'slots'}, in this order.`),
      h('div', { class: 'row-buttons' },
        h('button', { class: 'primary', onclick: () => app.paint(true) }, 'Pick the slots in the window'),
        h('button', { title: 'All the slots between the border', onclick: () => app.store.change((draft) => { delete draft.pages.layout; draft.pages.slots = interiorSlots(slotCount(draft), columns); return draft }) }, 'Between the border'),
        h('button', { class: 'danger', onclick: () => app.store.change((draft) => { draft.pages.slots = []; delete draft.pages.layout; return draft }) }, 'Clear'))),
    h('section', null,
      h('h4', null, 'Order and layout'),
      form.object(pagesType, ['pages'], { only: ['sort', 'descending', 'layout', 'cell'] })),
    h('section', null,
      h('h4', null, 'Items'),
      h('div', { class: 'row-buttons' },
        h('button', { onclick: () => { app.editor.popover = null; app.pickItem(TEMPLATE) } }, 'Edit the item of each element'),
        h('button', { onclick: () => {
          if (!pages.empty) app.store.set(['pages', 'empty'], { material: 'barrier', name: '&cNothing to show' })
          app.editor.popover = null
          app.pickItem('@pages.empty')
        } }, pages.empty ? 'Edit the item for no elements' : 'Add an item for no elements'))),
    h('section', null,
      h('div', { class: 'row-buttons' },
        h('button', { class: 'danger', onclick: () => { app.store.remove(['pages']); app.picked.item = null; app.editor.popover = null; app.draw() } }, 'Make it a normal menu'))))
}
