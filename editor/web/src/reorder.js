// Drag to reorder the rows of a list: take a row by its grip, move it over the others, and drop it where the line shows. The row is
// inserted there (not swapped with another), which is what an ordered list such as a list of actions needs. The grip also answers
// Alt + Up and Alt + Down, so the order can be changed without a pointer.

import { h } from './dom.js'

/**
 * Where a dragged row goes: the number of the other rows whose middle is above [y]. The row is put in front of the one at that
 * place of the list without it, so the result is also the row's new index.
 * @param {number[]} middles the middle of each row except the dragged one, top to bottom
 * @param {number} y the pointer
 */
export function dropIndex(middles, y) {
  let index = 0
  for (const middle of middles) if (middle < y) index++
  return index
}

/** The nearest ancestor that scrolls up and down, or null. */
function scroller(element) {
  for (let node = element.parentElement; node; node = node.parentElement) {
    const { overflowY } = getComputedStyle(node)
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight) return node
  }
  return null
}

/**
 * Makes the rows of [list] (its children of class `row`) draggable by the `.grip` in each.
 * @param {HTMLElement} list
 * @param {(from: number, to: number) => void} move called when a row is dropped at a place other than its own: the index it had and the one it gets
 */
export function reorderable(list, move) {
  const rows = () => [...list.children].filter((c) => c.classList.contains('row'))

  list.addEventListener('keydown', (event) => {
    const grip = event.target.closest?.('.grip')
    if (!grip || !event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
    const all = rows()
    const from = all.indexOf(grip.closest('.row'))
    const to = from + (event.key === 'ArrowUp' ? -1 : 1)
    if (from < 0 || to < 0 || to >= all.length) return
    event.preventDefault()
    move(from, to)
  })

  list.addEventListener('pointerdown', (event) => {
    const grip = event.target.closest?.('.grip')
    if (!grip || event.button !== 0) return
    const row = grip.closest('.row')
    const from = rows().indexOf(row)
    if (from < 0 || rows().length < 2) return
    event.preventDefault()
    grip.setPointerCapture(event.pointerId)
    row.classList.add('dragging')
    list.classList.add('reordering')
    const line = h('div', { class: 'drop-line' })
    list.append(line)
    const startY = event.clientY
    const parent = scroller(list)
    let y = event.clientY
    let to = from
    let frame = 0

    const place = () => {
      const others = rows().filter((r) => r !== row)
      to = dropIndex(others.map((r) => { const box = r.getBoundingClientRect(); return box.top + box.height / 2 }), y)
      const host = list.getBoundingClientRect()
      const edge = to < others.length ? others[to].getBoundingClientRect().top - 4 : others[others.length - 1].getBoundingClientRect().bottom + 4
      line.style.top = `${edge - host.top - 1}px`
      // The row follows the pointer; the others stay where they are.
      row.style.transform = `translateY(${y - startY}px)`
    }

    const scrollNear = () => {
      if (!parent) return
      const box = parent.getBoundingClientRect()
      const step = y < box.top + 36 ? -12 : y > box.bottom - 36 ? 12 : 0
      if (step) { parent.scrollTop += step; place() }
      frame = requestAnimationFrame(scrollNear)
    }

    const onMove = (e) => { y = e.clientY; place() }
    const finish = (e, apply) => {
      grip.removeEventListener('pointermove', onMove)
      grip.removeEventListener('pointerup', onUp)
      grip.removeEventListener('pointercancel', onCancel)
      cancelAnimationFrame(frame)
      row.classList.remove('dragging')
      row.style.transform = ''
      list.classList.remove('reordering')
      line.remove()
      if (apply && to !== from) move(from, to)
    }
    const onUp = (e) => finish(e, true)
    const onCancel = (e) => finish(e, false)
    grip.addEventListener('pointermove', onMove)
    grip.addEventListener('pointerup', onUp)
    grip.addEventListener('pointercancel', onCancel)
    place()
    frame = requestAnimationFrame(scrollNear)
  })
}
