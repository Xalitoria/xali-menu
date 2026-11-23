// A big file that is read by parts. It looks as much like a Blob as the zip reader needs (`size` and `slice(start, end).arrayBuffer()`), and
// fetches the parts that are asked for in blocks, each only once: for a pack that is too big to be sent at once through a relay.
// When a [BlockCache] keyed by the pack's SHA-256 hash is given, fetched blocks are persisted in IndexedDB so reopening the editor
// reuses unchanged Nexo/Oraxen/ItemsAdder pack blocks with zero relay traffic.

const DB_NAME = 'xalimenu-pack-cache'
const STORE_NAME = 'blocks'
const MAX_CACHED_BLOCKS = 256

/** Persistent browser-side block cache backed by IndexedDB, keyed by the resource pack's SHA-256 [hash]. */
export class BlockCache {
  /** @param {string} hash SHA-256 hex digest of the pack */
  constructor(hash) {
    this.hash = hash
    this.dbPromise = null
  }

  /** Returns a [BlockCache] when [hash] is provided and IndexedDB is available, otherwise null. */
  static forHash(hash) {
    if (!hash || typeof globalThis.indexedDB === 'undefined') return null
    return new BlockCache(hash)
  }

  open() {
    if (!this.dbPromise) {
      this.dbPromise = new Promise((resolve) => {
        try {
          const req = globalThis.indexedDB.open(DB_NAME, 1)
          req.onupgradeneeded = () => {
            const db = req.result
            if (!db.objectStoreNames.contains(STORE_NAME)) {
              const store = db.createObjectStore(STORE_NAME, { keyPath: 'key' })
              store.createIndex('at', 'at')
            }
          }
          req.onsuccess = () => resolve(req.result)
          req.onerror = () => resolve(null)
        } catch {
          resolve(null)
        }
      })
    }
    return this.dbPromise
  }

  /** @returns {Promise<Uint8Array|null>} */
  async get(blockSize, index) {
    const db = await this.open()
    if (!db) return null
    return new Promise((resolve) => {
      try {
        const tx = db.transaction(STORE_NAME, 'readonly')
        const req = tx.objectStore(STORE_NAME).get(`${this.hash}:${blockSize}:${index}`)
        req.onsuccess = () => {
          const rec = req.result
          resolve(rec?.data ? new Uint8Array(rec.data) : null)
        }
        req.onerror = () => resolve(null)
      } catch {
        resolve(null)
      }
    })
  }

  /** Stores [bytes] for [index] and prunes the oldest entries once the cache grows past [MAX_CACHED_BLOCKS]. */
  async put(blockSize, index, bytes) {
    const db = await this.open()
    if (!db) return
    return new Promise((resolve) => {
      try {
        const tx = db.transaction(STORE_NAME, 'readwrite')
        const store = tx.objectStore(STORE_NAME)
        store.put({ key: `${this.hash}:${blockSize}:${index}`, hash: this.hash, at: Date.now(), data: bytes.slice() })
        const countReq = store.count()
        countReq.onsuccess = () => {
          const excess = countReq.result - MAX_CACHED_BLOCKS
          if (excess <= 0) return
          let removed = 0
          const cursorReq = store.index('at').openCursor()
          cursorReq.onsuccess = () => {
            const cursor = cursorReq.result
            if (!cursor || removed >= excess) return
            cursor.delete()
            removed++
            cursor.continue()
          }
        }
        tx.oncomplete = () => resolve()
        tx.onerror = () => resolve()
      } catch {
        resolve()
      }
    })
  }
}

export class RangeFile {
  /**
   * @param {number} size the size of the file
   * @param {(start: number, end: number) => Promise<Uint8Array>} fetchRange the bytes from [start] up to [end] (exclusive); the answer may be shorter, when the other side cuts it
   * @param {number} [block] the size of what is fetched at a time
   * @param {{get(blockSize: number, index: number): Promise<Uint8Array|null>, put(blockSize: number, index: number, bytes: Uint8Array): Promise<void>}|null} [cache] optional persistent block cache
   */
  constructor(size, fetchRange, block = 1024 * 1024, cache = null) {
    this.size = size
    this.fetchRange = fetchRange
    this.block = block
    this.cache = cache
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

  /** The block [index], read from [cache] or fetched once. A fetch that fails is forgotten, so that it can be tried again. */
  get(index) {
    if (!this.blocks.has(index)) {
      const start = index * this.block
      const end = Math.min(start + this.block, this.size)
      const loading = (async () => {
        if (this.cache) {
          const hit = await this.cache.get(this.block, index).catch(() => null)
          if (hit && hit.byteLength === end - start) return hit
        }
        const fetched = await this.fill(start, end)
        if (this.cache) this.cache.put(this.block, index, fetched).catch(() => {})
        return fetched
      })().catch((error) => { this.blocks.delete(index); throw error })
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
