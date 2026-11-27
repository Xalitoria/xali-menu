import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSettingInput } from '../src/form.js'

test('parseSettingInput preserves typed booleans, numbers and JSON values for extension settings', () => {
  assert.equal(parseSettingInput('true', false, 'false'), true)
  assert.equal(parseSettingInput('false', true, 'true'), false)
  assert.equal(parseSettingInput('true', undefined, 'false'), true)
  assert.equal(parseSettingInput('{usable}', false, 'false'), '{usable}')

  assert.equal(parseSettingInput('42', 10, '10'), 42)
  assert.equal(parseSettingInput('-1', undefined, '0'), -1)
  assert.equal(parseSettingInput('3.5', undefined, '1.5'), 3.5)
  assert.equal(parseSettingInput('{delay}', 0, '0'), '{delay}')

  assert.deepEqual(parseSettingInput('["a", "b"]', ['x'], '["&7Line"]'), ['a', 'b'])
  assert.deepEqual(parseSettingInput('{"k":1}', undefined, '{"k":0}'), { k: 1 })
  assert.equal(parseSettingInput('[bad json', ['x'], '["&7Line"]'), '[bad json')

  assert.equal(parseSettingInput('tools', 'starter', 'tools'), 'tools')
  assert.equal(parseSettingInput('true', 'tools', 'tools'), 'true')
})
