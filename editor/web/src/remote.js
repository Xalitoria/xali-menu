// A big file that is read by parts. It looks as much like a Blob as the zip reader needs (`size` and `slice(start, end).arrayBuffer()`), and
// fetches the parts that are asked for in blocks, each only once: for a pack that is too big to be sent at once through a relay.

export class RangeFile {
  /**
   * @param {number} size the size of the file
   * @param {(start: number, end: number) => Promise<Uint8Array>} fetchRange the bytes from [start] up to [end] (exclusive); the answer may be shorter, when the other side cuts it
   * @param {number} block the size of what is fetched at a time
   */
  constructor(size, fetchRange, block = 1024 * 1024) {
    this.size = size
    this.fetchRange = fetchRange
    this.block = block
    /** @type {Map<number, Promise<Uint8Array>>} */
    this.blocks = new Map()
  }

  slice(start = 0, end = this.size) {
    const from = Math.max(0, Math.min(start, this.size))
    const to = Math.max(from, Math.min(end, this.size))
    return { size: to - from, arrayBuffer: () => this.read(from, to) }
  }

  /** The bytes from [start] to [end], as an ArrayBuffer. */
  async read(start, end) {
    const out = new Uint8Array(end - start)
    const first = Math.floor(start / this.block)
    const last = Math.floor((Math.max(end, start + 1) - 1) / this.block)
    const parts = await Promise.all(Array.from({ length: end > start ? last - first + 1 : 0 }, (_, i) => this.get(first + i)))
    parts.forEach((part, i) => {
      const at = (first + i) * this.block
      const from = Math.max(start, at)
      const to = Math.min(end, at + part.length)
      if (to > from) out.set(part.subarray(from - at, to - at), from - start)
    })
    return out.buffer
  }

  /** The block [index], fetched once. A fetch that fails is forgotten, so that it can be tried again. */
  get(index) {
    if (!this.blocks.has(index)) {
      const start = index * this.block
      const end = Math.min(start + this.block, this.size)
      const loading = this.fill(start, end).catch((error) => { this.blocks.delete(index); throw error })
      this.blocks.set(index, loading)
    }
    return this.blocks.get(index)
  }

  /** The bytes of [start, end), asking again for what an answer left out. */
  async fill(start, end) {
    const out = new Uint8Array(end - start)
    let at = start
    while (at < end) {
      const part = await this.fetchRange(at, end)
      if (part.length === 0) throw new Error('The pack ended early')
      out.set(part.subarray(0, end - at), at - start)
      at += part.length
    }
    return out
  }
}
