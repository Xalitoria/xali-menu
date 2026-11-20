// Renders the Bedrock Edition preview inside the editor's stage: native SimpleForm, ModalForm, CustomForm,
// redirect cards, and compatibility banners when viewing a menu as Bedrock PC, Mobile or Console.

import { h } from './dom.js'
import { icon } from './icons.js'
import { render as mcText } from './mctext.js'
import { viewOf } from './scale.js'
import { TEMPLATE } from './pages.js'
import { titleForDevice, simpleFormButtons, compatibilityNotes } from './bedrock.js'

/**
 * Compatibility strip shown above the stage whenever the editor previews a Bedrock device.
 * Lists the resolved presentation mode and any compatibility notes, with a button to open the Bedrock settings popover.
 */
export function bedrockBanner(app, device, presentation, defaultMode) {
  const menu = app.store.value
  const notes = compatibilityNotes(menu, device, defaultMode)
  const label = {
    inventory: `Inventory (${device.ui === 'POCKET' ? 'Pocket UI' : 'Classic UI'})`,
    redirect: `Redirects to ${presentation.menu}`,
    simple: menu.bedrock?.buttons?.length ? 'Bedrock SimpleForm (custom buttons)' : 'Bedrock SimpleForm (auto-converted)',
    modal: 'Bedrock ModalForm',
    custom: 'Bedrock CustomForm',
  }[presentation.kind] ?? presentation.kind
  return h('div', { class: 'bedrock-banner' },
    h('div', { class: 'bedrock-banner-head' },
      h('span', { class: 'bedrock-pill' }, `${device.label} · ${label}`),
      h('button', { class: 'small', onclick: () => { app.editor.popover = 'bedrock'; app.draw() } }, 'Bedrock settings')),
    notes.length > 0 && h('ul', { class: 'bedrock-notes' },
      notes.map((n) => h('li', { class: n.level }, n.message))))
}

/**
 * Interactive preview of a Bedrock form (`simple`, `modal`, `custom`) or redirect (`redirect`), styled like
 * Minecraft: Bedrock Edition's native form dialogs.
 */
export function bedrockFormView(app, device, presentation) {
  const menu = app.store.value
  const shown = app.shownMenu()
  const cfg = menu.bedrock ?? {}
  const title = titleForDevice(shown, device)
  const narrow = device.device === 'MOBILE'

  if (presentation.kind === 'redirect') {
    const canJump = Boolean(app.server && app.menuNames?.includes(presentation.menu))
    return h('div', { class: `bedrock-frame ${narrow ? 'mobile' : 'wide'}` },
      h('div', { class: 'bedrock-header' }, h('span', { class: 'bedrock-title' }, 'Bedrock Redirect')),
      h('div', { class: 'bedrock-body redirect' },
        h('p', null, `${device.label} players opening this menu are redirected to `, h('code', null, presentation.menu), '.'),
        canJump && h('button', { class: 'primary', onclick: () => app.open(presentation.menu) }, `Open ${presentation.menu}`)))
  }

  if (presentation.kind === 'modal') {
    const confirmText = cfg.confirm?.text || '&aConfirm'
    const cancelText = cfg.cancel?.text || '&cCancel'
    return h('div', { class: `bedrock-frame ${narrow ? 'mobile' : 'wide'}` },
      h('div', { class: 'bedrock-header' }, h('span', { class: 'bedrock-title' }, mcText(title)), h('span', { class: 'bedrock-x' }, '×')),
      h('div', { class: 'bedrock-body' },
        cfg.content && h('div', { class: 'bedrock-content' }, multiline(cfg.content)),
        h('div', { class: 'bedrock-modal-actions' },
          h('button', { class: 'bedrock-btn', onclick: () => { app.editor.popover = 'bedrock'; app.draw() } }, mcText(confirmText)),
          h('button', { class: 'bedrock-btn', onclick: () => { app.editor.popover = 'bedrock'; app.draw() } }, mcText(cancelText)))))
  }

  if (presentation.kind === 'custom') {
    const fields = Array.isArray(cfg.fields) ? cfg.fields : []
    return h('div', { class: `bedrock-frame ${narrow ? 'mobile' : 'wide'}` },
      h('div', { class: 'bedrock-header' }, h('span', { class: 'bedrock-title' }, mcText(title)), h('span', { class: 'bedrock-x' }, '×')),
      h('div', { class: 'bedrock-body' },
        fields.length === 0 && h('p', { class: 'muted' }, 'No custom form fields yet. Open Bedrock settings to add inputs, toggles, dropdowns or sliders.'),
        h('div', { class: 'bedrock-fields' }, fields.map((field) => renderField(field))),
        h('button', { class: 'bedrock-btn submit', onclick: () => { app.editor.popover = 'bedrock'; app.draw() } }, 'Submit')))
  }

  // SimpleForm (explicit buttons or auto-converted from menu items + pages)
  const { buttons } = simpleFormButtons(menu, shown, { page: app.page, pages: app.previewPages() })
  const view = viewOf(2)
  return h('div', { class: `bedrock-frame ${narrow ? 'mobile' : 'wide'}` },
    h('div', { class: 'bedrock-header' }, h('span', { class: 'bedrock-title' }, mcText(title)), h('span', { class: 'bedrock-x' }, '×')),
    h('div', { class: 'bedrock-body' },
      cfg.content && h('div', { class: 'bedrock-content' }, multiline(cfg.content)),
      buttons.length === 0 && h('p', { class: 'muted' }, 'No visible buttons on this form. Add menu items with click actions, or define bedrock.buttons.'),
      h('div', { class: 'bedrock-buttons' }, buttons.map((btn) => {
        const picked = btn.id && btn.id === app.picked.item
        const thumb = buttonIcon(btn, view)
        return h('button', {
          class: `bedrock-btn simple ${picked ? 'active' : ''}`,
          onclick: () => {
            if (btn.paging) app.turnPage(btn.paging)
            else if (btn.id) app.pickItem(btn.id)
            else if (btn.paged) app.pickItem(TEMPLATE)
            else { app.editor.popover = 'bedrock'; app.draw() }
          },
        },
        thumb && h('span', { class: 'bedrock-btn-icon' }, thumb),
        h('span', { class: 'bedrock-btn-text' },
          h('span', { class: 'bedrock-btn-main' }, mcText(btn.label)),
          btn.sub && h('span', { class: 'bedrock-btn-sub' }, mcText(btn.sub))),
        btn.choices.length > 1 && h('span', { class: 'bedrock-badge', title: `Tapping opens an action picker: ${btn.choices.map((c) => c.label).join(' / ')}` }, `${btn.choices.length} actions`))
      }))))
}

function buttonIcon(btn, view) {
  if (btn.image && /^https?:\/\//i.test(btn.image)) {
    return h('img', { src: btn.image, alt: '', class: 'bedrock-img' })
  }
  if (btn.item) return icon(btn.item, view.device, view.ratio)
  if (btn.image && !btn.image.includes('/')) return icon({ material: btn.image }, view.device, view.ratio)
  return null
}

function renderField(field) {
  const type = String(field?.type ?? '').toLowerCase()
  const chip = type !== 'label' && field?.id ? h('code', { class: 'bedrock-var' }, `{${field.id}}`) : null
  switch (type) {
    case 'label':
      return h('div', { class: 'bedrock-field label' }, multiline(field.text ?? ''))
    case 'input':
      return h('div', { class: 'bedrock-field' },
        h('div', { class: 'bedrock-field-head' }, mcText(field.text ?? ''), chip),
        h('input', { type: 'text', value: field.default ?? '', placeholder: field.placeholder ?? '', readonly: true }))
    case 'toggle':
      return h('div', { class: 'bedrock-field toggle' },
        h('div', { class: 'bedrock-field-head' }, mcText(field.text ?? ''), chip),
        h('input', { type: 'checkbox', checked: Boolean(field.default), disabled: true }))
    case 'dropdown': {
      const opts = Array.isArray(field.options) ? field.options : []
      return h('div', { class: 'bedrock-field' },
        h('div', { class: 'bedrock-field-head' }, mcText(field.text ?? ''), chip),
        h('select', { disabled: true }, opts.map((o, i) => h('option', { selected: i === (field.default ?? 0) }, o))))
    }
    case 'slider': {
      const min = field.min ?? 0
      const max = field.max ?? 100
      const step = field.step ?? 1
      const val = field.default ?? min
      return h('div', { class: 'bedrock-field' },
        h('div', { class: 'bedrock-field-head' }, mcText(field.text ?? ''), h('span', { class: 'muted' }, `${val} (${min}–${max})`), chip),
        h('input', { type: 'range', min, max, step, value: val, disabled: true }))
    }
    case 'step-slider': {
      const steps = Array.isArray(field.steps) ? field.steps : []
      const idx = Math.min(Math.max(0, field.default ?? 0), Math.max(0, steps.length - 1))
      return h('div', { class: 'bedrock-field' },
        h('div', { class: 'bedrock-field-head' }, mcText(field.text ?? ''), h('span', { class: 'muted' }, steps[idx] ?? ''), chip),
        h('input', { type: 'range', min: 0, max: Math.max(0, steps.length - 1), step: 1, value: idx, disabled: true }))
    }
    default:
      return h('div', { class: 'bedrock-field muted' }, `Field (${type || 'unknown'})`)
  }
}

function multiline(text) {
  const lines = String(text ?? '').split('\n')
  return lines.map((line, i) => h('div', { key: i }, mcText(line)))
}
