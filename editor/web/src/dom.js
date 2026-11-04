// A small helper for building elements, so the views read as the structure they produce.

/**
 * Creates an element. [attrs] sets properties and attributes (`class`, `title`, `onclick`, `value`...); strings, numbers
 * and nodes in [children] are appended, and false, null and undefined are skipped.
 * @returns {HTMLElement}
 */
export function h(tag, attrs, ...children) {
  const element = document.createElement(tag)
  for (const [name, value] of Object.entries(attrs ?? {})) {
    if (value === false || value === null || value === undefined) continue
    if (name === 'class') element.className = value
    else if (name.startsWith('on') && typeof value === 'function') element.addEventListener(name.slice(2), value)
    else if (name in element && name !== 'list' && name !== 'style') element[name] = value
    else element.setAttribute(name, value === true ? '' : value)
  }
  append(element, children)
  return element
}

function append(element, children) {
  for (const child of children.flat(Infinity)) {
    if (child === false || child === null || child === undefined) continue
    element.append(child instanceof Node ? child : document.createTextNode(String(child)))
  }
}

/** Replaces the content of [element] with [children]. */
export function fill(element, ...children) {
  element.replaceChildren()
  append(element, children)
  return element
}

/** Text with `backticks` shown as code, for the comments of the schema. */
export function doc(text) {
  const span = document.createElement('span')
  text.split(/(`[^`]+`)/).forEach((part) => {
    if (part.startsWith('`') && part.length > 2) span.append(h('code', null, part.slice(1, -1)))
    else span.append(part)
  })
  return span
}
