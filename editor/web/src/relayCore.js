// Reusable end-to-end encrypted relay crypto primitive (PBKDF2-HMAC-SHA256 + AES-256-GCM).
// Shared by XaliMenu (`salt: 'xalimenu:session:v2'`, `aadPrefix: 'xm1'`) and
// XaliCrates (`salt: 'xalicrates:session:v1'`, `aadPrefix: 'xc1'`).

const subtle = globalThis.crypto.subtle
const encoder = new TextEncoder()
const decoder = new TextDecoder()

export const bytesOf = (text) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0))
export const base64 = (bytes) => {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(s)
}
export const url64 = (bytes) => base64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

/**
 * Builds domain-separated `deriveCode`, `seal`, `open`, and `importKey` helpers for one plugin's relay protocol.
 * @param {{ salt: string, aadPrefix: string, iterations?: number }} config
 */
export function createRelayCrypto({ salt, aadPrefix, iterations = 100_000 }) {
  const saltBytes = encoder.encode(salt)

  async function deriveCode(code) {
    const material = await subtle.importKey('raw', encoder.encode(code), 'PBKDF2', false, ['deriveBits'])
    const raw = new Uint8Array(await subtle.deriveBits(
      { name: 'PBKDF2', salt: saltBytes, iterations, hash: 'SHA-256' },
      material,
      48 * 8,
    ))
    const session = url64(raw.subarray(0, 16))
    const key = await subtle.importKey('raw', raw.subarray(16, 48), 'AES-GCM', false, ['encrypt', 'decrypt'])
    return { session, key }
  }

  async function seal(key, session, direction, value) {
    const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
    const data = await subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: encoder.encode(`${aadPrefix}:${session}:${direction}`) },
      key,
      encoder.encode(JSON.stringify(value)),
    )
    const out = new Uint8Array(12 + data.byteLength)
    out.set(iv)
    out.set(new Uint8Array(data), 12)
    return out
  }

  async function open(key, session, direction, bytes) {
    if (bytes.length < 28) throw new Error('The message is too short')
    const plain = await subtle.decrypt(
      { name: 'AES-GCM', iv: bytes.subarray(0, 12), additionalData: encoder.encode(`${aadPrefix}:${session}:${direction}`) },
      key,
      bytes.subarray(12),
    )
    return JSON.parse(decoder.decode(plain))
  }

  const importKey = (text) => subtle.importKey('raw', bytesOf(text), 'AES-GCM', false, ['encrypt', 'decrypt'])

  return { deriveCode, seal, open, importKey }
}
