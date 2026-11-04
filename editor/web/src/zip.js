// Reads a zip file in the browser without unpacking it all: the list of entries comes from the directory at the end, and an
// entry is only read (and inflated) when asked for. A resource pack can be a hundred megabytes; this keeps it on the disk.

const EOCD = 0x06054b50
const CENTRAL = 0x02014b50
const LOCAL = 0x04034b50

export class Zip {
  /** @param {Blob} blob */
  constructor(blob, entries) {
    this.blob = blob
    this.entries = entries
  }

  /** @param {Blob} blob @returns {Promise<Zip>} */
  static async open(blob) {
    const tail = new DataView(await blob.slice(Math.max(0, blob.size - 65557)).arrayBuffer())
    let at = tail.byteLength - 22
    while (at >= 0 && tail.getUint32(at, true) !== EOCD) at--
    if (at < 0) throw new Error('This is not a zip file')
    const count = tail.getUint16(at + 10, true)
    const size = tail.getUint32(at + 12, true)
    const offset = tail.getUint32(at + 16, true)
    if (offset === 0xffffffff) throw new Error('Zip files over 4 GB are not supported')
    const directory = new DataView(await blob.slice(offset, offset + size).arrayBuffer())
    const decoder = new TextDecoder()
    const entries = new Map()
    let position = 0
    for (let i = 0; i < count; i++) {
      if (directory.getUint32(position, true) !== CENTRAL) throw new Error('The zip directory is damaged')
      const method = directory.getUint16(position + 10, true)
      const compressed = directory.getUint32(position + 20, true)
      const nameLength = directory.getUint16(position + 28, true)
      const extraLength = directory.getUint16(position + 30, true)
      const commentLength = directory.getUint16(position + 32, true)
      const local = directory.getUint32(position + 42, true)
      const name = decoder.decode(new Uint8Array(directory.buffer, position + 46, nameLength))
      if (!name.endsWith('/')) entries.set(name, { method, compressed, local })
      position += 46 + nameLength + extraLength + commentLength
    }
    return new Zip(blob, entries)
  }

  has(path) { return this.entries.has(path) }

  /** The paths in the zip. */
  list() { return [...this.entries.keys()] }

  /** @returns {Promise<Uint8Array>} */
  async bytes(path) {
    const entry = this.entries.get(path)
    if (!entry) throw new Error(`${path} is not in the zip`)
    const header = new DataView(await this.blob.slice(entry.local, entry.local + 30).arrayBuffer())
    if (header.getUint32(0, true) !== LOCAL) throw new Error('The zip is damaged')
    const start = entry.local + 30 + header.getUint16(26, true) + header.getUint16(28, true)
    const raw = this.blob.slice(start, start + entry.compressed)
    if (entry.method === 0) return new Uint8Array(await raw.arrayBuffer())
    if (entry.method !== 8) throw new Error(`${path} uses a compression this editor cannot read`)
    return new Uint8Array(await new Response(raw.stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer())
  }

  async text(path) { return new TextDecoder().decode(await this.bytes(path)) }

  async json(path) { return JSON.parse(await this.text(path)) }
}
