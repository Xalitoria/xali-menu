# XaliMenu plan

Goal: meet, and where cheap to, exceed AbstractMenus in capability while keeping XaliMenu's own kebab-case HOCON schema, Java-clean addon API, Folia-safe runtime, Bedrock form support and web editor. Completed work is documented in [`README.md`](README.md), [`editor/README.md`](editor/README.md), [`relay/README.md`](relay/README.md), [`REFERENCE.md`](REFERENCE.md) and [`live/README.md`](live/README.md); this file tracks only what remains.

## Remaining features

### Plugin & schema

- **Items catalog**: a built-in catalog listing configured items (analogous to `values` / `inventory`, for reusable item lists in paged menus).
- **Player inventory extensions**: `matrix` support for the player's inventory area, animation frames targeting the player's inventory, and armor / off-hand slots.
- **Multi-word command tab completion**: tab completion (`complete`) for unregistered multi-word commands (currently works only for registered one-word commands).
- **Extra validation**: numeric range bounds on placeholder-capable text fields (`money.amount`, `level.amount` when given as fixed numbers), and `ConfigChecker`-style key/value validation for `config.yml`.

### Web editor & relay

- **Hosted `/relay/` deployment**: deploy `menu/relay/worker.js` (`npx wrangler deploy` or add `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` secrets for `.github/workflows/relay.yml`) so `https://menu.xali.to/relay/*` is live.
- **Real-time collaborative editing**: live presence and synced edits when multiple editors open the same shared session (currently each viewer works on their own copy until saving).
- **Visual polish**:
  - Animated enchantment glint (currently a single static frame).
  - Armor preview from the side/back or rendered on a player's own skin (currently the front of a plain figure).
  - Inline sentence editors (`src/inline.js`) for list-only actions and conditions (`give-item`, `all`, `gamemode`, `world`, `region`, `and`, `or`, `one-of`, `not`).

## Not yet tested live

Unit tests cover the logic below, but these paths have not yet been verified against live plugins or clients:

- **Bedrock & multi-device support**: end-to-end testing against a running Geyser/Floodgate server and Bedrock clients (PC Classic, Mobile Pocket/Touch, Console/Controller).
- **Short 12-char `/xmenu edit` & `/xmenu edit --auto-reload` on hosted `https://menu.xali.to/relay/`**: live round-trip through the public `/relay/` route with Nexo pack/glyphs/items and pending-reload vs auto-reload saves.
- **ItemsAdder and Oraxen**: resource pack discovery, glyph resolution, custom item models and `ItemsAdder.getPackUrl` against real plugin installations; real S3 `pack-url` CORS download and `NexoPlugin.packServer().packUrl()` on a running Nexo.
- **Optional permission/meta hooks**: `group` action/condition with a Vault permission provider; `meta` action with LuckPerms.
- **Activators & input edge cases**: `entity` activator and `sneaking`/`require` activator gates; `accept` deposit slots in creative mode and non-chest inventories; real client typing in Paper `Dialog` and ViaBackwards `ANVIL` prompts.

## Out of scope (left out on purpose)

- **AM file compatibility** — XaliMenu keeps its own HOCON schema.
- **Imperative slot-mutating actions** (`place-item`, `button-set`, `item-refresh`) — replaced by declarative `view` gates, `update` and variables.
- **`recipe-add`** — modifying server crafting recipes is outside a menu plugin's scope.
- **Citizens** — use FancyNpcs (or match entity NPCs with the `entity` activator).
- **JavaScript conditions & DeluxeMenus-only features** — `[meta]` / `has meta`, `javascript`, `is object`, `[placeholder]`.
