// Minecraft Java Edition version & resource pack `pack_format` registry (1.8 through 26.3+).
// Used by the Asset Manager, Resource Pack overlay resolver, Font loader, and Item Model renderer so older
// servers and packs render with the exact model format, texture paths, and overlays of their version.

/**
 * Known Minecraft Java Edition release ranges ordered newest-first.
 * `branch` is the matching branch on `InventivetalentDev/minecraft-assets`.
 * `packFormat` is the primary resource pack format (major number; 1.21.9+ uses major.minor).
 */
export const PACK_FORMATS = [
  { version: '26.3',   min: [26, 3, 0],  packFormat: 97, branch: '26.3' },
  { version: '26.2',   min: [26, 2, 0],  packFormat: 90, branch: '26.2' },
  { version: '26.1.2', min: [26, 1, 2],  packFormat: 84, branch: '26.1.2' },
  { version: '26.1',   min: [26, 1, 0],  packFormat: 84, branch: '26.1' },
  { version: '1.21.11',min: [1, 21, 11], packFormat: 75, branch: '1.21.11' },
  { version: '1.21.10',min: [1, 21, 10], packFormat: 69, branch: '1.21.10' },
  { version: '1.21.9', min: [1, 21, 9],  packFormat: 69, branch: '1.21.9' },
  { version: '1.21.8', min: [1, 21, 8],  packFormat: 64, branch: '1.21.8' },
  { version: '1.21.7', min: [1, 21, 7],  packFormat: 64, branch: '1.21.7' },
  { version: '1.21.6', min: [1, 21, 6],  packFormat: 63, branch: '1.21.6' },
  { version: '1.21.5', min: [1, 21, 5],  packFormat: 55, branch: '1.21.5' },
  { version: '1.21.4', min: [1, 21, 4],  packFormat: 46, branch: '1.21.4' },
  { version: '1.21.3', min: [1, 21, 3],  packFormat: 42, branch: '1.21.3' },
  { version: '1.21.2', min: [1, 21, 2],  packFormat: 42, branch: '1.21.2' },
  { version: '1.21.1', min: [1, 21, 1],  packFormat: 34, branch: '1.21.1' },
  { version: '1.21',   min: [1, 21, 0],  packFormat: 34, branch: '1.21' },
  { version: '1.20.6', min: [1, 20, 6],  packFormat: 32, branch: '1.20.6' },
  { version: '1.20.5', min: [1, 20, 5],  packFormat: 32, branch: '1.20.5' },
  { version: '1.20.4', min: [1, 20, 4],  packFormat: 22, branch: '1.20.4' },
  { version: '1.20.3', min: [1, 20, 3],  packFormat: 22, branch: '1.20.3' },
  { version: '1.20.2', min: [1, 20, 2],  packFormat: 18, branch: '1.20.2' },
  { version: '1.20.1', min: [1, 20, 1],  packFormat: 15, branch: '1.20.1' },
  { version: '1.20',   min: [1, 20, 0],  packFormat: 15, branch: '1.20' },
  { version: '1.19.4', min: [1, 19, 4],  packFormat: 13, branch: '1.19.4' },
  { version: '1.19.3', min: [1, 19, 3],  packFormat: 12, branch: '1.19.3' },
  { version: '1.19.2', min: [1, 19, 2],  packFormat: 9,  branch: '1.19.2' },
  { version: '1.19',   min: [1, 19, 0],  packFormat: 9,  branch: '1.19' },
  { version: '1.18.2', min: [1, 18, 2],  packFormat: 8,  branch: '1.18.2' },
  { version: '1.18',   min: [1, 18, 0],  packFormat: 8,  branch: '1.18' },
  { version: '1.17.1', min: [1, 17, 1],  packFormat: 7,  branch: '1.17.1' },
  { version: '1.16.5', min: [1, 16, 2],  packFormat: 6,  branch: '1.16.5' },
  { version: '1.16.1', min: [1, 15, 0],  packFormat: 5,  branch: '1.16.1' },
  { version: '1.14.4', min: [1, 14, 0],  packFormat: 4,  branch: '1.14.4' },
  { version: '1.13.2', min: [1, 13, 0],  packFormat: 4,  branch: '1.13.2' },
  { version: '1.12.2', min: [1, 11, 0],  packFormat: 3,  branch: '1.12.2' },
  { version: '1.10.2', min: [1, 9, 0],   packFormat: 2,  branch: '1.10.2' },
  { version: '1.8.9',  min: [1, 6, 1],   packFormat: 1,  branch: '1.8.9' },
]

export const LATEST_VERSION = '26.3'

/** Parses a Minecraft version string (`"1.21.4"`, `"1.20.4-R0.1-SNAPSHOT"`, `"26.1.2"`, `"26.3"`) into `[major, minor, patch]`. */
export function parseVersion(raw) {
  const text = String(raw ?? '').trim()
  const match = text.match(/(\d+)\.(\d+)(?:\.(\d+))?/)
  if (!match) return null
  return [Number(match[1]), Number(match[2]), Number(match[3] ?? 0)]
}

function cmpTuple(a, b) {
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] - b[i]
  }
  return 0
}

/**
 * Resolves rendering capabilities and asset branch for a Minecraft version string (or numeric `packFormat`).
 * @param {string|number|null|undefined} versionOrFormat
 */
export function resolveVersion(versionOrFormat) {
  if (typeof versionOrFormat === 'number' && Number.isFinite(versionOrFormat)) {
    const entry = PACK_FORMATS.find((e) => e.packFormat <= versionOrFormat) ?? PACK_FORMATS[PACK_FORMATS.length - 1]
    return buildInfo(entry.version, versionOrFormat, entry.branch)
  }
  const parsed = parseVersion(versionOrFormat)
  if (!parsed) return buildInfo(LATEST_VERSION, 97, LATEST_VERSION)
  const clean = parsed[2] > 0 ? `${parsed[0]}.${parsed[1]}.${parsed[2]}` : `${parsed[0]}.${parsed[1]}`
  const match = PACK_FORMATS.find((e) => cmpTuple(parsed, e.min) >= 0) ?? PACK_FORMATS[PACK_FORMATS.length - 1]
  // Exact known version uses its exact branch; unknown patch uses closest matching known branch.
  const exact = PACK_FORMATS.find((e) => e.version === clean)
  return buildInfo(clean, match.packFormat, exact ? exact.branch : match.branch)
}

function buildInfo(version, packFormat, branch) {
  return {
    version,
    packFormat,
    branch,
    /** MC 1.21.4+ (format >= 46): `assets/<ns>/items/<name>.json` item model definitions. */
    hasItemDefinitions: packFormat >= 46,
    /** MC 1.21.6+ (format >= 63): clips GUI items to the 16x16 slot unless `oversized_in_gui: true` is set. */
    clipsNonOversizedInGui: packFormat >= 63,
    /** MC 1.9 – 1.21.3 (formats 2..45): `overrides` predicates inside `models/item/<name>.json`. */
    hasLegacyOverrides: packFormat < 46,
    /** MC 1.6 – 1.12.2 (formats 1..3): `textures/items/` and `textures/blocks/` plural folders. */
    hasLegacyTextureDirs: packFormat <= 3,
    /** MC 1.13+ (format >= 4): JSON font providers in `assets/<ns>/font/*.json`. */
    hasFontJson: packFormat >= 4,
    /** MC 1.13+ (format >= 4): JSON language files (`en_us.json` vs legacy `en_us.lang`). */
    hasLangJson: packFormat >= 4,
    /** MC 1.20+ (format >= 15): `paletted_permutations` atlas sources for armor trims. */
    hasPalettedPermutations: packFormat >= 15,
    /** MC 1.20.2+ (format >= 18): `gui/sprites/` split GUI sprites and pack `overlays`. */
    hasGuiSprites: packFormat >= 18,
    hasOverlays: packFormat >= 18,
    /** MC 1.20.5+ (format >= 32): item data components (`item_model` introduced in 1.21.2/format 42). */
    hasItemModelComponent: packFormat >= 42,
  }
}

/** Normalizes a `min_format` / `max_format` / `formats` bound (`46`, `[46, 0]`, `{ min_inclusive, max_inclusive }`). */
function formatNumber(bound) {
  if (typeof bound === 'number') return bound
  if (Array.isArray(bound) && typeof bound[0] === 'number') return bound[0] + (bound[1] ?? 0) / 1000
  return null
}

/** Returns whether [packFormat] satisfies a resource pack `formats` / `supported_formats` / `min_format`..`max_format` spec. */
export function matchesFormatRange(spec, packFormat) {
  if (!spec || typeof packFormat !== 'number') return false
  if (spec.min_format !== undefined || spec.max_format !== undefined) {
    const min = formatNumber(spec.min_format) ?? -Infinity
    const max = formatNumber(spec.max_format) ?? Infinity
    return packFormat >= min && packFormat <= max
  }
  const f = spec.formats ?? spec.supported_formats
  if (typeof f === 'number') return packFormat === f
  if (Array.isArray(f) && f.length >= 2) return packFormat >= f[0] && packFormat <= f[1]
  if (f && typeof f === 'object') {
    const min = typeof f.min_inclusive === 'number' ? f.min_inclusive : -Infinity
    const max = typeof f.max_inclusive === 'number' ? f.max_inclusive : Infinity
    return packFormat >= min && packFormat <= max
  }
  return false
}

/**
 * Parses a resource pack's `pack.mcmeta` JSON into normalized format metadata and overlay entries.
 * Supports legacy `pack_format`, 1.20+ `supported_formats`, 1.20.2+ `overlays.entries`, and 1.21.9+ `min_format`/`max_format`.
 */
export function parsePackMeta(mcmeta) {
  const pack = mcmeta?.pack ?? {}
  const minFmt = formatNumber(pack.min_format)
  const maxFmt = formatNumber(pack.max_format)
  const primary = typeof pack.pack_format === 'number'
    ? pack.pack_format
    : (maxFmt ?? minFmt ?? null)
  const overlays = (mcmeta?.overlays?.entries ?? [])
    .filter((e) => typeof e?.directory === 'string' && e.directory.trim())
    .map((e) => ({
      directory: e.directory.replace(/^\/+|\/+$/g, ''),
      spec: e,
    }))
  return {
    packFormat: primary,
    minFormat: minFmt,
    maxFormat: maxFmt,
    supportedFormats: pack.supported_formats ?? null,
    description: pack.description ?? '',
    overlays,
  }
}

/** Returns the ordered overlay directory prefixes (highest priority first) active for [targetPackFormat]. */
export function activeOverlays(packMeta, targetPackFormat) {
  if (!packMeta?.overlays?.length || typeof targetPackFormat !== 'number') return []
  // In Minecraft, later entries in `overlays.entries` override earlier entries.
  return packMeta.overlays
    .filter((o) => matchesFormatRange(o.spec, targetPackFormat))
    .map((o) => o.directory)
    .reverse()
}
