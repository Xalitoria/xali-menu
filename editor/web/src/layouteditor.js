// The layout of a menu as a picture: the grid of the window, and the palette of the legend. A character of the palette is chosen and painted into cells by clicking or dragging
// across them; a cell shows the item its character stands for. The legend is edited in the palette (a character and the item it names), so the two keys are one control.
// Painting is shown in the grid while the pointer is down and is written to the menu when it is let go, so that the window behind is not drawn again for every cell.

import { h } from './dom.js'
import { icon } from './icons.js'
import { slotCount } from './check.js'
import { COLUMNS, grid, paint, rowsOf, counts, strays } from './layout.js'

/** What to do when the pointer is let go after painting (write the grid to the menu), set by the grid being painted; one listener serves every grid. */
let finishPainting = null
if (typeof window !== 'undefined') window.addEventListener('pointerup', () => { const finish = finishPainting; finishPainting = null; finish?.() })

/** The tool that is painting: a character of the legend, or a space (erase). It stays when the panel is drawn again. */
let chosen = ' '

const itemOf = (menu, id) => (id && menu.items?.[id]) || null

/** The layout key of the menu: the grid and the palette. */
export function layoutEditor(store, path, value, menu) {
  const live = () => store.get([]) ?? menu
  const rowsOfMenu = () => Math.max(1, Math.ceil(slotCount(live()) / COLUMNS))
  let layout = Array.isArray(value) ? [...value] : []
  const board = h('div', { class: 'layout-grid', role: 'grid' })
  const palette = h('div', { class: 'layout-palette' })
  const note = h('small', { class: 'muted' })

  const cellFace = (char) => {
    const legend = live().legend ?? {}
    const item = itemOf(live(), legend[char])
    if (char === ' ') return h('span', { class: 'cell-empty' }, '')
    return item ? icon(item, 2) : h('span', { class: 'cell-char', title: `'${char}' is not in the legend, so it is nothing` }, char)
  }

  const drawBoard = () => {
    const total = slotCount(live())
    const cells = grid(layout, rowsOfMenu())
    board.replaceChildren(...cells.flatMap((row, r) => row.map((char, c) => {
      const slot = r * COLUMNS + c
      const button = h('button', { class: `layout-cell ${char === ' ' ? 'blank' : ''}`, 'data-row': r, 'data-column': c, disabled: slot >= total, title: slot >= total ? 'Not a slot of this menu' : `Slot ${slot}`, 'aria-label': `Slot ${slot}: ${char === ' ' ? 'nothing' : char}` }, cellFace(char))
      return button
    })))
    const { unknown } = strays(layout, live().legend)
    note.textContent = unknown.length ? `${unknown.map((c) => `'${c}'`).join(', ')} ${unknown.length === 1 ? 'is' : 'are'} not in the legend, so ${unknown.length === 1 ? 'it is' : 'they are'} nothing.` : ''
  }

  const commit = () => store.set(path, rowsOf(grid(layout, layout.length)))

  // Painting: the pointer down on a cell paints it, and so does entering another while it is down; the menu is written when it is let go.
  const at = (event) => { const cell = event.target.closest?.('.layout-cell'); return cell && !cell.disabled ? [Number(cell.dataset.row), Number(cell.dataset.column)] : null }
  const put = (place) => {
    if (!place) return
    const next = paint(layout, rowsOfMenu(), place[0], place[1], chosen)
    if (JSON.stringify(next) === JSON.stringify(layout)) return
    layout = next
    drawBoard()
  }
  board.addEventListener('pointerdown', (event) => { const place = at(event); if (!place || event.button > 2) return; event.preventDefault(); finishPainting = commit; if (event.button === 2) chosen = ' '; put(place) })
  board.addEventListener('contextmenu', (event) => event.preventDefault())
  board.addEventListener('pointerover', (event) => { if (finishPainting === commit) put(at(event)) })

  // The palette: the legend, a character and the item it names.
  const drawPalette = () => {
    const legend = live().legend ?? {}
    const used = counts(layout)
    const ids = Object.keys(live().items ?? {})
    // The tool is marked in place, not by drawing the palette again: a button that is taken out of the page by its own click is taken for a click outside the panel, which closes it.
    const tool = (char, face, label) => h('button', { class: `palette-tool ${chosen === char ? 'on' : ''}`, 'data-char': char, role: 'radio', 'aria-checked': chosen === char, title: label,
      onclick: () => { chosen = char; for (const b of palette.querySelectorAll('.palette-tool')) { const on = b.dataset.char === char; b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)) } } }, face, h('b', null, char === ' ' ? '␣' : char))
    const entry = ([char, id]) => h('div', { class: 'palette-entry' },
      tool(char, itemOf(live(), id) ? icon(itemOf(live(), id), 2) : h('span', { class: 'cell-char' }, '?'), `${char} is ${id}${used.has(char) ? `, in ${used.get(char)} ${used.get(char) === 1 ? 'cell' : 'cells'}` : ', in no cell'}`),
      h('select', { 'aria-label': `The item of ${char}`, onchange: (e) => store.set(['legend', char], e.target.value) },
        ids.includes(id) ? null : h('option', { value: id, selected: true }, `${id} (not an item)`), ids.map((i) => h('option', { value: i, selected: i === id }, i))),
      h('button', { class: 'icon remove small', title: `Take ${char} out of the legend`, 'aria-label': `Take ${char} out of the legend`, onclick: () => { if (chosen === char) chosen = ' '; store.remove(['legend', char]) } }, '×'))
    const newChar = h('input', { type: 'text', maxlength: 2, class: 'layout-char', placeholder: 'x', 'aria-label': 'A new character', spellcheck: false })
    const newItem = h('select', { 'aria-label': 'Its item' }, ids.map((i) => h('option', { value: i }, i)))
    const add = () => {
      const char = [...newChar.value.trim()][0]
      if (!char || !newItem.value) return
      chosen = char
      store.set(['legend', char], newItem.value)
    }
    palette.replaceChildren(
      h('div', { class: 'palette-tools', role: 'radiogroup' }, tool(' ', h('span', { class: 'cell-empty' }), 'Erase: paints nothing (a right click erases too)'), Object.entries(legend).map(entry)),
      ids.length === 0 ? h('small', { class: 'muted' }, 'Add an item first: a character stands for one.') : h('div', { class: 'palette-add' }, newChar, h('span', { class: 'muted' }, 'is'), newItem, h('button', { class: 'small', onclick: add }, '+ Add to the legend')))
  }

  drawBoard()
  drawPalette()
  return h('div', { class: 'layout-editor' }, palette, board, note,
    h('div', { class: 'burst-row' }, h('button', { class: 'small', onclick: () => { layout = []; drawBoard(); commit() } }, 'Clear the layout'),
      h('small', { class: 'muted' }, 'Choose a character, then click or drag over the cells. Right click erases.')))
}

/** The legend is edited in the palette of the layout, above; its own place only says so. */
export function legendNote() {
  return h('small', { class: 'muted' }, 'Edited in the palette of the layout above: a character and the item it names.')
}
