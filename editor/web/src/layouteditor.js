// The menu matrix as a picture: a single global grid for the whole menu (`matrix.cells`) and the palette mapping each character to an item
// (`matrix.map`). A character of the palette is chosen and painted into cells by clicking or dragging across them; a cell shows the item its
// character stands for. Painting is shown in the grid while the pointer is down and written to the menu when it is let go, so that the window
// behind is not drawn again for every cell.

import { h } from './dom.js'
import { icon } from './icons.js'
import { slotCount } from './check.js'
import { claimsOf } from './placement.js'
import { COLUMNS, grid, paint, rowsOf, counts, strays } from './layout.js'

/** What to do when the pointer is let go after painting (write the grid to the menu), set by the grid being painted; one listener serves every grid. */
let finishPainting = null
if (typeof window !== 'undefined') window.addEventListener('pointerup', () => { const finish = finishPainting; finishPainting = null; finish?.() })

/** The tool that is painting: a character of the matrix map, or a space (erase). It stays when the panel is drawn again. */
let chosen = ' '

const itemOf = (menu, id) => (id && menu.items?.[id]) || null

/** Explicit `slot`/`slots` claims per slot, so the matrix grid can warn when a cell overlaps an item placed outside the matrix. */
function explicitClaims(menu) {
  const bySlot = new Map()
  for (const [id, item] of Object.entries(menu.items ?? {})) {
    for (const slot of claimsOf(item, 'items')) {
      if (!bySlot.has(slot)) bySlot.set(slot, [])
      bySlot.get(slot).push(id)
    }
  }
  return bySlot
}

/** The global `matrix` (`cells` + `map`) of the menu: the palette and the paintable grid. */
export function matrixEditor(store, path = ['matrix'], value = null, menu = null) {
  const live = () => store.get([]) ?? menu ?? {}
  const liveMatrix = () => (store.get(path) ?? value ?? {})
  const rowsOfMenu = () => Math.max(1, Math.ceil(slotCount(live()) / COLUMNS))
  let cells = Array.isArray(liveMatrix().cells) ? [...liveMatrix().cells] : []
  const board = h('div', { class: 'layout-grid', role: 'grid' })
  const palette = h('div', { class: 'layout-palette' })
  const note = h('small', { class: 'muted' })

  const writeMatrix = (nextCells, nextMap) => {
    const cleanCells = rowsOf(grid(nextCells, nextCells.length))
    const cleanMap = Object.fromEntries(Object.entries(nextMap ?? {}))
    if (cleanCells.length === 0 && Object.keys(cleanMap).length === 0) store.remove(path)
    else store.set(path, { cells: cleanCells, map: cleanMap })
  }

  const cellFace = (char) => {
    const map = liveMatrix().map ?? {}
    const item = itemOf(live(), map[char])
    if (char === ' ') return h('span', { class: 'cell-empty' }, '')
    return item ? icon(item, 2) : h('span', { class: 'cell-char', title: `'${char}' is not in matrix.map, so it is nothing` }, char)
  }

  const drawBoard = () => {
    const current = live()
    const map = liveMatrix().map ?? {}
    const total = slotCount(current)
    const explicit = explicitClaims(current)
    const matrix = grid(cells, rowsOfMenu())
    board.replaceChildren(...matrix.flatMap((row, r) => row.map((char, c) => {
      const slot = r * COLUMNS + c
      const mappedId = char === ' ' ? null : (map[char] ?? null)
      const others = (explicit.get(slot) ?? []).filter((id) => id !== mappedId)
      const overlap = Boolean(mappedId && others.length > 0)
      const title = slot >= total
        ? 'Not a slot of this menu'
        : overlap
          ? `Slot ${slot}: ${mappedId} (also claimed by ${others.join(', ')} via slot/slots)`
          : mappedId ? `Slot ${slot}: ${mappedId}` : `Slot ${slot}`
      return h('button', {
        class: `layout-cell ${char === ' ' ? 'blank' : ''} ${overlap ? 'overlap' : ''}`,
        'data-row': r, 'data-column': c, disabled: slot >= total, title,
        'aria-label': `Slot ${slot}: ${char === ' ' ? 'nothing' : char}`,
      }, cellFace(char))
    })))
    const { unknown } = strays(cells, map)
    note.textContent = unknown.length ? `${unknown.map((c) => `'${c}'`).join(', ')} ${unknown.length === 1 ? 'is' : 'are'} not in matrix.map, so ${unknown.length === 1 ? 'it is' : 'they are'} nothing.` : ''
  }

  const commit = () => writeMatrix(cells, liveMatrix().map ?? {})

  // Painting: the pointer down on a cell paints it, and so does entering another while it is down; the menu is written when it is let go.
  const at = (event) => { const cell = event.target.closest?.('.layout-cell'); return cell && !cell.disabled ? [Number(cell.dataset.row), Number(cell.dataset.column)] : null }
  const put = (place) => {
    if (!place) return
    const next = paint(cells, rowsOfMenu(), place[0], place[1], chosen)
    if (JSON.stringify(next) === JSON.stringify(cells)) return
    cells = next
    drawBoard()
  }
  board.addEventListener('pointerdown', (event) => { const place = at(event); if (!place || event.button > 2) return; event.preventDefault(); finishPainting = commit; if (event.button === 2) chosen = ' '; put(place) })
  board.addEventListener('contextmenu', (event) => event.preventDefault())
  board.addEventListener('pointerover', (event) => { if (finishPainting === commit) put(at(event)) })

  // The palette: `matrix.map`, a single character and the item it names.
  const drawPalette = () => {
    const map = liveMatrix().map ?? {}
    const used = counts(cells)
    const ids = Object.keys(live().items ?? {})
    // The tool is marked in place, not by drawing the palette again: a button that is taken out of the page by its own click is taken for a click outside the panel, which closes it.
    const tool = (char, face, label) => h('button', { class: `palette-tool ${chosen === char ? 'on' : ''}`, 'data-char': char, role: 'radio', 'aria-checked': chosen === char, title: label,
      onclick: () => { chosen = char; for (const b of palette.querySelectorAll('.palette-tool')) { const on = b.dataset.char === char; b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)) } } }, face, h('b', null, char === ' ' ? '␣' : char))
    const entry = ([char, id]) => h('div', { class: 'palette-entry' },
      tool(char, itemOf(live(), id) ? icon(itemOf(live(), id), 2) : h('span', { class: 'cell-char' }, '?'), `${char} is ${id}${used.has(char) ? `, in ${used.get(char)} ${used.get(char) === 1 ? 'cell' : 'cells'}` : ', in no cell'}`),
      h('select', { 'aria-label': `The item of ${char}`, onchange: (e) => writeMatrix(cells, { ...liveMatrix().map, [char]: e.target.value }) },
        ids.includes(id) ? null : h('option', { value: id, selected: true }, `${id} (not an item)`), ids.map((i) => h('option', { value: i, selected: i === id }, i))),
      h('button', { class: 'icon remove small', title: `Remove '${char}' from matrix.map`, 'aria-label': `Remove '${char}' from matrix.map`, onclick: () => {
        if (chosen === char) chosen = ' '
        const nextMap = { ...(liveMatrix().map ?? {}) }
        delete nextMap[char]
        writeMatrix(cells, nextMap)
      } }, '×'))
    const newChar = h('input', { type: 'text', maxlength: 2, class: 'layout-char', placeholder: 'x', 'aria-label': 'A new character', spellcheck: false })
    const newItem = h('select', { 'aria-label': 'Its item' }, ids.map((i) => h('option', { value: i }, i)))
    const add = () => {
      const char = [...newChar.value.trim()][0]
      if (!char || !newItem.value) return
      chosen = char
      writeMatrix(cells, { ...(liveMatrix().map ?? {}), [char]: newItem.value })
    }
    palette.replaceChildren(
      h('div', { class: 'palette-tools', role: 'radiogroup' }, tool(' ', h('span', { class: 'cell-empty' }), 'Erase: paints nothing (a right click erases too)'), Object.entries(map).map(entry)),
      ids.length === 0 ? h('small', { class: 'muted' }, 'Add an item first: each character maps to an item in the menu.') : h('div', { class: 'palette-add' }, newChar, h('span', { class: 'muted' }, '→'), newItem, h('button', { class: 'small', onclick: add }, '+ Add to map')))
  }

  drawBoard()
  drawPalette()
  return h('div', { class: 'layout-editor' }, palette, board, note,
    h('div', { class: 'burst-row' }, h('button', { class: 'small', onclick: () => { cells = []; store.remove(path) } }, 'Clear matrix'),
      h('small', { class: 'muted' }, 'Choose a character, then click or drag over the cells. Right click erases.')))
}
