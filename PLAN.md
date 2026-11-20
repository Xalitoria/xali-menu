# XaliMenu plan

Goal: meet, and where it is cheap to, exceed [AbstractMenus](/mount/storage/Code/java/abstractmenus) (AM). This file
replaces the earlier plan, which was lost when the workspace was split into one repository per module. It was rebuilt on
2026-10-31 from a comparison of the two code bases.

XaliMenu keeps its own schema (kebab-case HOCON through kotlinx.serialization) and its addon API, which has no Kotlin
types in it. It does not aim for file compatibility with AM. It aims for the same abilities.

## Where AM stands, and where XaliMenu does

| Area | AM | XaliMenu now |
|---|---|---|
| Menu kinds | simple, animated, generated (paged) | step 3: simple, animated, paged (with layouts) |
| Paging | `GeneratedMenu`, catalogs, `pageNext` / `pagePrev` | step 1, below |
| Activators | ~18 (command, click item / block / entity / NPC, join, chat, region, sign, lever, button, plate, swap items) | step 2: commands, item, join, chat, swap-hands, block (sign, lever, button, plate), entity |
| Actions | ~67 | 41 |
| Rules | ~30 | 26 |
| Item properties | ~34 | the common ones |
| Variables | SQLite | memory, SQLite and MySQL, with sync. **Ahead.** |
| Addon API | `Types` registry, Java and Kotlin types | actions, conditions, item sources, no Kotlin types. **Ahead.** |
| Importer | none | DeluxeMenus. **Ahead.** |
| Folia | FoliaLib | region schedulers throughout |

AM weaknesses that XaliMenu avoids on purpose:

- Paging counts elements before it checks each item's rules, so a page can come out short. XaliMenu filters first.
- The element being drawn is global state on the menu (`currentObject`), set again on a click. XaliMenu binds it to the
  context of each slot.
- The catalogs are a closed set. XaliMenu's can be added to by other plugins.

## Steps

Each step ends with tests, the README section, and a commit.

### 1. Paging and catalogs (done, tested live)

- `pages` in a menu: a catalog, the slots it fills, an item template, optional sort and an `empty` item.
- Catalogs: `players`, `worlds`, `values`, `range`, and `XaliMenuApi.registerCatalog` for addons.
- `{page}`, `{pages}`, `{total}`, `{page_next}`, `{page_prev}` in any text of the menu, `{index}` and the catalog's own
  fields in the template; `%xalimenu_page%` and the like for other plugins.
- The `page` action (`by`, `to`), `MenuContext.pageBy` / `pageTo`.
- Not in this step: the `entities` catalog (needs an entity scope that is safe on Folia), and a catalog of items.
- Titles update while the menu is open (`{page}` and placeholders), through the deprecated but working
  `InventoryView.setTitle`, which resends the window with the new title. Sent only when the text changes.
  Titles are the usual component (color library, then MiniMessage) serialized to legacy text with hex kept.
- Tested live on the dev server's Folia 26.2 with a mineflayer bot (see below): page contents, `{page}` on items,
  the title following the page, element-bound clicks, `page` by / to last, and the join activator.
- Covered by unit tests: the layout (`layoutPage`), the catalogs, the context scope, the `page` action, reading the menu
  and external catalogs.

### 2. Activators (done, tested live)

- `open-with` in a menu: `item`, `join`, `chat`, `swap-hands`, `block` and `entity`, each with a `require` gate, `cancel`
  and `args` that can use the event's values (`{message}`, `{block}`, ...).
- One `block` type covers AM's click-block, sign, lever, button and plate: keys or `#tags`, a position, and `PHYSICAL`
  for stepping on a block.
- Matching is pure functions (`Activators.kt`) covered by unit tests. Live, with the bot: `chat` (exact, ignoring case,
  canceled, and other messages left alone), `item` (right click opens, a swing does not), `swap-hands`, `join` and
  `block` (a button at a position). Not tried live: `entity`, and `sneaking` / `require` gates.
- `npc`: a click on an NPC of FancyNpcs (soft dependency, its event is read by reflection so nothing links to its API), by
  name or id, `LEFT` / `RIGHT` / `ANY`, with `cancel` stopping the NPC's own actions. Citizens is not supported on purpose.
  Tested live (`live/bot/npc.js`, `XM_PLUGINS=FancyNpcs`): right click opens, an unmatched left click does not, a
  sneaking left click opens with `{npc_id}`.
- `region-enter` / `region-leave` (WorldGuard, compiled against its API as `compileOnly`, a soft dependency): from the
  move and teleport events, once a block is crossed, comparing the regions at the block with those the player stood in
  (`RegionChange`, tested); the menu opens two ticks later, since a teleport closes what is open. Joining inside a region is
  silent, a change of world leaves all and enters all. A `region` condition too. Tested live on Paper with FAWE and
  WorldGuard 7.0.19 (`live/bot/region.js`): entering, moving inside (silent), leaving, and the condition inside and out.
- Addon activators: `XaliMenuApi.registerActivator(owner, "plugin:name", type)` returns a source; the addon calls
  `source.fire(player, values)` when its event happens, and the activators of that type whose filter (built by `type` from the
  keys of the menu file) matches the values open their menus. Matching on the caller's thread, gate and opening on the
  player's; `fire` says whether to cancel. Read as `Activator.Addon` (the file's `{type = "plugin:name", ...}` is rewritten
  by `packExternals`, keeping `require`, `cancel` and `args` in place). Unit tested (reading, matching, removal with the
  plugin), and live with a plugin written in Java (`live/addon/XmDemo.java`, `live/bot/addon.js`): no match, a match, the
  gate denying, `cancel` as returned, and `validate` on keys the type refuses.

### 3. Menu kinds (done, tested live)

- **Animations**: `animation { loop, on-start, on-end, frames }`, each frame with `delay`, `clear`, `require`, `on-start`,
  `on-end` and `items`. The frames are an overlay over the menu (`MenuView.overlay`) drawn first in `draw()`, and each frame
  schedules the next on the player's region, so there is no polling loop. `FrameCursor` is the pure part and is tested.
- **Layouts** for paged menus: `pages.layout` and `pages.cell` as an alternative to `slots`.
- **Nested menus**: an element's values reach the template's `open` action as `{name}`, so no new code was needed; it
  is documented and tested.
- Tested live with the bot: frame timing (500 ms steps to within a tick), additive and clearing frames, the base item
  coming back, `on-start` / `on-end`, a layout filling its cells over two pages, and an element opening a nested menu.
- **Layout for any menu** (done after the seven steps): `layout` (rows of nine characters) and `legend` (character to item
  name) put each item in every slot where its character stands, besides its own `slot` / `slots` (`Menu.placed()`). Tested
  by unit tests and live (`live/bot/layout.js`).

### 4. Actions (done, tested live)

- **Items**: `give-item` (any item a menu can show, with `overflow` drop or abort), `take-item` (aborts when there are too few,
  so it can stand before what is given for them), `clear-items`. Matching is by material, plain name and custom model.
- **The viewer**: `potion` (add, remove, clear all), `teleport` (`~` relative coordinates, other worlds, on the async
  teleport that is safe on Folia), `gamemode`, `health` and `food` (`SET` / `ADD` / `TAKE`, held in range), `level`.
- **Showing things**: `title`, `boss-bar` (hidden after `duration`), `book`, `particle`.
- **Asking**: `input`, a prompt that closes the menu, validates the reply with a regex and runs `then` with `{input}`;
  `cancel` word, `timeout`, `on-cancel`. It asks in a dialog (Paper's `Dialog` with a text input, for clients of 1.21.6 and
  later), in an anvil's rename field for older ones (ViaVersion says which a client is) or in chat (`mode`). Dialog buttons
  are read from `PlayerCustomClickEvent`, since Paper's callbacks are queued for a tick Folia may not run.
- **Flow**: `all`, `random` (pick `count`), `run-for` (an action list for each element of a catalog, with `{index}`), `log`.
- **Permissions**: `group` (Vault) and `meta` (LuckPerms, through its command).
- Unit tests: removals across stacks, range and coordinate helpers, prompt verdicts, quoting for the console, flow actions,
  and reading and writing every new action. Live (Folia 26.2, bot): give / take with name match and the abort when there is
  nothing to take, clear, potion packet, teleport relative on x and z, game mode, health and food, levels, title packets,
  boss bar shown and hidden, book packet, particle packet and a bad particle failing alone, `run-for` and `random`, the
  `input` prompt (invalid reply, valid reply, timeout, cancel word) and ordinary chat afterwards.
- Text input, live (Folia 26.2, bot; `live/bot/text.js`): the anvil (opens with the paper named `initial`, the result shows the typed
  text, an invalid one is turned down and the anvil stays, a valid one closes it and runs `then`, leaving it or the timeout
  cancels, shift-click and pick-up move nothing and the real inventory is the same before and after) and the dialog (the packet
  has the title, the field with `initial` and `max_length`, an invalid reply brings it back with the reason, a valid one runs
  `then`, a replay with nothing waiting does nothing, the cancel button and the timeout cancel and the timeout clears the
  dialog). With ViaVersion installed the client's version is read (776). Not tested: a real client typing in a dialog, an old client
  through ViaBackwards (so the anvil is only reached with `mode = ANVIL`), Escape in a dialog (the client sends nothing).
- Input slots (`accept` on an item, `Deposits.kt`): an item offered from the cursor or by shift-click is read from the server's state
  (`CursorOffer`, `SlotOffer`), `then` runs, and only if it ran to its end (`runActions` now says so) is the amount taken, after
  looking again. `reject` runs for a mismatch. A menu that draws in the player's inventory has none (a warning when it is read).
  Live (`live/bot/deposit.js`, Folia 26.2, bot): the cursor took 2 at a time and refused the last 1, another item was refused and stayed,
  a payment (`take-item` first) with no gold took nothing and with 5 gold took the gold and the item, shift-click from the
  inventory, and the inventory was exactly what the sequence leaves after the menu was closed. Not tested: creative mode, other
  menu types than a chest, number-key or hotbar swaps (cancelled, as before).
- Compatibility: `Item` gained the `accept` parameter, which changes its constructor and `copy`, so an add-on compiled against an
  earlier API calling `Item(...)` or `item.copy(...)` needs a rebuild (`NoSuchMethodError` otherwise). Nothing is published with the
  old shape yet; once something is, a new field goes in with a hidden constructor for the old signature.
- Not tested live: `group` (needs Vault's permission provider) and `meta` (needs LuckPerms).
- `skin` (a player's name or a link, `slim` for a picture) and `skin-reset` through SkinsRestorer: looked up off the region
  threads, stored for the next visit, put on on the player's thread; one that can't be found is a console warning. Tested
  live (`live/bot/skin.js`): the stored skin after the set (Notch's id), after the reset (gone), and a name that doesn't exist.
- Left out on purpose: `place-item` and the other per-slot item actions of AM (`button-set`, `item-refresh`, ...) change a
  menu while it is open, which `view` gates, `update` and the variables already do without code that edits the window by
  hand; `recipe-add` changes the server's recipes, which is not a menu's job.

### 5. Conditions (done, tested live)

- Parity: `free-slots`, `held-item`, `group` (Vault), `gamemode`, `health`, `food`, `level`, `experience`, `world`, `online`,
  `online-count`, `variable-exists`, `and`, `or`, `one-of`, `at-least`, `chance`. The ranges take `min` / `max` as numbers or
  placeholders. The DeluxeMenus importer maps `has exp` (points or `level: true`).
- Beyond: `cooldown` and a `cooldown` action. A condition only looks, so starting the cooldown is an action; the state is the
  variable `cooldown_<key>` with an expiry, so it is kept wherever the variables are (shared across servers with the right
  rule), and a store that has not loaded counts as running. `once` is a cooldown with no duration.
- Unit tests: the combinations and that they stop as soon as the answer is known, the ranges and their placeholders,
  settings that are refused, reading and writing every new condition. Live (bot, items shown or hidden by `view`): free
  slots with and without items, held item, game mode, health, food, level, experience on both sides of its limit, worlds,
  online, the four combinations, `chance` at both ends, a cooldown ending and a once-only one staying, and `variable-exists`.
- Not tested live: `group` (needs a Vault permission provider). Not done: JavaScript.

### 6. Item properties (done, tested live)

- New: `item-name`, `damage`, `rarity`, `glow`, `max-stack-size`, `hide-tooltip`, `item-model`, `food`, `stored-enchants`,
  `potion-type`, `trim`, `book`, `firework` (rocket and star), `fish`. Paper's item meta where it covers a property and data
  components for the rest (food, tooltip display). Shulker color is the material. Skull owner and texture were already the
  `head:` and `texture:` materials.
- A value that can't be used is reported when the menu is read (`validate`). An item that throws while it is built used to
  take the whole menu's drawing down (found by a deliberately bad item, `damage = -3`); it is now a barrier, noted once, and
  the values that Paper would refuse are held in range.
- The DeluxeMenus importer maps `damage`, `rarity`, `hide_tooltip`, `enchantment_glint_override`, `trim_pattern` and
  `trim_material` and `item_model`. The names were checked against the DeluxeMenus 1.14.1 jar (the option names it reads, and
  its six bundled example menus import with no warning but its own stray `sweet: true`). It has no `open_with_item`. It also
  reads `light_level`, `tooltip_style`, `lore_append_mode` and `model_data_component`, which are warned about and ignored.
- Checked against the running plugin (DeluxeMenus 1.14.1 on Paper 26.2, `live/bot/compare.js`: both open the same menu, the
  windows and the chat from clicks are compared). That found and fixed: `data` is the `damage` of a current material (was
  turned into a variant); `minimum_requirements` leaves non-optional requirements mandatory (a gate's `minimum` did the
  opposite, now it follows DeluxeMenus); the requirement aliases (`item`, `money`, `perm`, `contains`, `equals`, `regex`, the
  long comparison names, `does not have item`), `has permissions` and `string length` were missing; `[jsonbroadcast]`; and an
  `amount` above the stack size of the material was cut (up to 99 now). The harness was also using stale util / color jars.
  Not carried over: `[meta]` and the `has meta` requirement (DeluxeMenus' persistent meta; the variables are the nearest
  thing, but the placeholders would have to be rewritten), `javascript`, `is object`, `[placeholder]`.
- Tested live: the player data of a bot that was given every kind of item shows each component with the value written
  (the server has no `/data` for entities on Folia, so the saved data was read instead).
- `attributes` (attack damage, max health and the like, per slot group), `tooltip-style` and `equippable` (slot, model, camera
  overlay, swappable, dispensable, damage on hurt). Bad names are reported by `validate`; tested live (the saved data of a
  player holds each component as written, and `validate` lists the wrong ones).
- `tool` (default speed, damage per block, rules over block keys and `#tags`). Tested live: the saved stick holds the rule with
  the tag expanded; wrong blocks and tags are reported by `validate`.

### 7. Housekeeping (done, tested live)

- `/xmenu reload <menu>` (one file; a file that cannot be read leaves the old menu in place), `/xmenu info <menu>`,
  `/xmenu validate [menu]` (every problem of every menu and every unreadable file, without opening any), and menu names
  complete in all of them.
- `/xmenu inspect [player]`, the debug view: per slot, the items that claim it, which one is drawn, and each check of the
  `view` gate with its result (`Gate.explain`). The first lines go to chat, all of them to the console.
- The bot-driven live test is in the repository: `menu/live` (`setup.sh`, `server.sh`, `bot/*.js`, the menus they use and a
  README). Tried from scratch in a fresh folder: it built, set up, started a server and ran the action and condition runs.
- Messages for these are in the language file (`commands.tools`), written back on the first start.
- Tab completion for a menu's `args`: a `complete` map from an argument name to its words, `@players` or `@worlds`. A name
  that isn't an argument is a `validate` problem. Unit tested (`MenuArgs.suggestions`) and live (`live/bot/complete.js`).
  Not done for the commands of several words, which aren't registered.

### 8. Dev experience and the web editor (done, tested live)

- KDoc in the style of the Gentoo wiki (`CONTRIBUTING.md` says what that means here), every property of the model documented,
  and a map of the code with how to add an action, condition, item property or activator.
- `editor` module: `web/` is a static site for GitHub Pages (no build step, no dependencies, tests with `node --test`), and Kotlin
  code writes `web/schema.json` from the serializer descriptors and the comments of the sources. `:menu:editor:test` fails when it is
  stale. The form, window, checks, undo, HOCON reading and writing, and the resource pack code are in `web/src`.
- Plugin side (`paper/.../editor`): `/xmenu edit`, tokens (hashed, expiring), a JDK HTTP endpoint (off by default; CORS only for the
  editor's site and localhost; menu names cannot leave the folder; saves are read through the model first, written atomically, old
  file kept), the pack and glyph names of Nexo, Oraxen and ItemsAdder, and the lookup of custom items. Without the endpoint the
  link carries the menu (gzip, base64url, after the `#`).
- Resource pack: the zip is read in the browser (directory first, entries inflated on demand), fonts follow `reference` providers,
  title text is drawn with bitmap and space providers, and a glyph is moved by rewriting the space characters at the start of the
  title (a glyph of another font gets a `<font:...>` tag). Item pictures come from the pack through item definitions and custom model
  data. Tested against Nexo's real pack (loaded, glyphs drawn behind the slots, a glyph put in and moved).
- Live (`XM_EDITOR=1`): `/xmenu edit` link, curl against every route (token, origin, preflight, unknown menu, path traversal, a refused
  and a checked menu, a new menu saved, the backup), and the page in a browser connected to the server: menus listed, the server's
  pack and 8 glyph names loaded, a title with `<glyph:menu_items>` drawn, and a save that changed the file.
- Not tested: ItemsAdder and Oraxen (only Nexo's data was at hand; ItemsAdder's glyph names come from its content files, the characters, pack URL and item models from its API, none run against a real ItemsAdder),
  custom item pictures through `/v1/items` with a real Nexo item, and the deploy workflow itself (it runs on GitHub).
- Items drawn from their models (second pass of the editor): item definitions, parents, element baking after the game's FaceBakery,
  the `gui` display transform and the item lights, rasterized in software (`raster.js`); checked against icons rendered by the game
  (`curl` of the wiki's Invicon files, compared pixel by pixel in the browser: stone, furnace, stairs, sword within a few pixels,
  creeper head and shulker box exact, chests about 85 %). Heads, chests and shulker boxes are built from boxes (`special.js`).
  The window, title font, counts, durability bar and tooltip use the game's own textures and fonts; skins are fetched through
  `/v1/skin`, which only fetches from textures.minecraft.net; `/v1/items` also says how much each item wears.
- Player inventory (done after that; since step 11 the items say `inventory-slots` instead of being in an `inventory` list): items draw in the rows under it (slots 0 to 35, any item property, `view`, clicks, `update`). It is done by rewriting the packets that fill the window (`screen/`, PacketEvents or ProtocolLib, whichever is installed, the first preferred), so the server's inventory is never touched and nothing can be lost or duplicated; with neither, the rows are left alone and the console says so once. An earlier version swapped the items into the real inventory with a saved copy; it worked, but a bug there could lose items, so it was replaced. Tested live with each library and with none, and the player data saved with the menu open holds the real items only. The editor has the slots, a list and the form for them. Not done: layouts and animation frames for it, armor and off hand slots.
- Not done: trims, animated glint. (Shields with patterns were added later: `base-color` and `banner` on a `shield` item, set by the plugin as the shield's components and drawn by `shieldLayers`, matching the game's icon of a plain shield on 95 % of its pixels; signs are flat sprites and were already drawn. Unifont was added later, see `src/unihex.js`: the page reads the game's `unihex` fonts from the public copy, else through `/v1/game-file`, which the plugin fetches from Mojang's asset index and keeps; checked live in a browser with Japanese, Cyrillic and symbols.) Icons are drawn smooth (4x oversampled), the GUI scale is 1 to 4 or fit, and the window sits centered in a stage with room for oversized glyphs. Every one of 43 more icons the game rendered (fences, doors, lanterns, anvils, hoppers...) matches within 0 to 9 % of pixels, except beds, which the game draws from code: those and other unbuilt entity models are now a tile instead of a partial picture.
- Third pass: item definitions' `transformation` (the foot of a bed is a model moved one block, which was the offset seen on beds; now 4 %),
  banners with patterns (mask 5 %, color 1 % against the game's icon), potion colors (`/v1/colors`: the average of the effects' colors as the
  game works it out), and a searchable item list for `material`. Not done: trims, animated glint.

### 9. Easier configuration: one screen, and catalogs the editor understands (done)

- The Editor tab is one screen: the window always shown and filling the space between the toolbars, the menu's properties in a toolbar above
  it (type and size as dropdowns, a popover per group), the items as a list with the cell id editable, a glyph bar under the window when a
  pack, glyphs or custom items are around. The Raw tab is a draft applied by `Ctrl+S` or a button, and follows the editor the other way.
- Catalogs tell the editor what their elements hold and, for other plugins' catalogs, what their settings are:
  `registerCatalog(owner, name, values, settings, type)`, in `schema.json` for the built-in ones and `/v1/info` for all. The editor's Pages
  panel picks the catalog from a dropdown, the slots by clicking the window, and draws the template with the examples; the template is an
  entry of the item list with the values as chips. The preview turns pages (a page and a half of example elements) and shows the page of an
  empty catalog while its item is picked.
- A `menus` catalog (`name`, `title`), for a menu of menus, and an `inventory` catalog (`slot`, `material`, `amount`, `name`) of what
  the viewer carries.
- Connected to a server, the preview shows what the catalog lists now for the admin instead of the examples: `POST /v1/elements` reads
  the catalog as a menu file would (so a catalog of another plugin works) and calls it on the player's region thread, as a menu does.
- Tested by unit tests (JS and Kotlin), by driving the editor in a browser with a faked server, and live (`live/bot/editor.js`, Folia
  26.2): `/v1/info` with the values and settings of every catalog, and `/v1/elements` for players, worlds, menus, inventory (named
  items, armor and empty slots), range (the first 500 of 1198), values sorted backwards, a catalog that does not exist, one missing a
  key, a request without a catalog, and a bad token. Not done: an `entities` catalog (needs an entity scope that is safe on Folia).

### 10. Sharing the editor through a relay (done, tested live)

- For a public editor like LuckPerms', without hosting anything on GitHub Pages: `menu/relay` is a Node mailbox (no dependencies, in memory,
  sessions that end when the plugin stops asking). `/xmenu share [menu]` opens a session at `editor.relay.url` and prints
  `#relay=...&session=...&key=...`; the plugin only connects out. Requests and answers are AES-256-GCM, with the key only in the fragment
  (the relay sees bytes; session and direction are in the associated data; old, repeated and foreign requests are dropped).
- The page side is `RelayServer` (`web/src/relay.js`), the same `Server` as the direct endpoint, so everything else (live elements, glyphs,
  checks) works unchanged. The routes moved to `EditorApi`, used by the HTTP endpoint and the relay.
- Several people can use a link. What they save is checked and held (`PendingEdits`); admins get a message, `/xmenu edits` lists, and
  `apply <id>` / `drop <id>` decide. Nothing from a shared link is written unseen, since a menu can run commands.
- Tests: relay (`node --test relay/test`), the page's crypto and flow (`web/test/relay.test.js`), Kotlin (`EditorRelayTest`: a message made by the
  page's JavaScript is read by the Kotlin code, the held edits). Live (`live/bot/relay.js`, Folia 26.2): the link, `info`/`menu`/`save` through
  a real relay, the save held and announced, a menu that cannot load refused, `/xmenu edits`, `apply` (the file changed, the old kept),
  a second `apply` unknown, and `share stop` ending the session at the relay.
- Read-only links (after): `/xmenu share readonly [menu]`; the plugin refuses saves from it and `/v1/info` says `readOnly` (live-tested through a relay).
- Not done: live seeing of each other's changes while editing
  (each person works on their own copy), a hosted relay.
- **Big packs** (after the relay): `GET /v1/pack` answers `Range` (4 MB at most), the page reads the zip through `RangeFile` (1 MB blocks, directory first),
  and `/v1/info` says `packSize` and `packUrl`: `editor.pack-url`, else Nexo's `NexoPlugin.instance().packServer().packUrl()` (S3, LobFile, Polymath, self-hosted).
  The page downloads from the host when it may (https, CORS, same size as the local file) and falls back to the server's parts. Unit tests: `byteRange`,
  `RangeFile` with a zip built by hand, the relay's `packFile`. Live: a 12.5 MB pack through the relay, its directory and one entry read with 3 MB fetched, and
  `packUrl` in `/v1/info` from `XM_PACK_URL`. Not tested: a real S3 download (no bucket at hand) and Nexo's `packUrl()` on a running Nexo (the API was read
  from the 1.29.1 jar).

## Planned (asked for, not started)

- **Bedrock through Geyser**: the web editor will need changes, and what a menu looks like may differ between mobile and PC.

### 11. Reusing items anywhere in the window (done, tested in a browser; not live on a server)

- `inventory-slots` on an item of `items`: the same item is drawn in those slots of the player's inventory too, so it is defined once and used in the menu and under it.
  It replaces the `inventory` list of step 8 (the plugin was not in production, so there is no migration): the checks cover its range, an item with no slot at all, and `accept`. Unit tested (`PlayerInventory`).
- The editor: any item can be dragged onto any slot of either part (the list too; Alt or Ctrl copies), picked and placed in more slots (**Place in more slots**), shown as
  chips with where it is drawn, and an empty slot offers the items already defined.
  Rules in `src/placement.js`, tested in `test/placement.test.js`.
- Not done: a `layout` for the player's inventory, armor and off hand slots. Not tried: the drawing in a real window with `inventory-slots` (the packet code is unchanged, it only gets more entries).

### 12. Editor and schema finishing: dynamic values, registries (done; not live on a server)

- `amount` takes a number or text with placeholders; `dynamic-amount` is gone. The same for `model`, `damage` and `max-stack-size`, and for the switches `unbreakable`, `glow` and `hide-tooltip`
  (`DynamicInt`, `DynamicBoolean` in `net.xalitoria.menu.model`; the schema's type `dynamic` with `of`). Unit tested (`ItemProperties`, `SchemaTest`, `dynamic.test.js`, `check.test.js`).
- The editor: a line between properties, names that wrap instead of being cut, red remove buttons, the player's rows no longer grayed out and labeled, registry dropdowns
  (`registry.js`, from the language file), the tipped arrow follows its potion type (a quiet change now asks the server for the color, and `potions.js` gives one without a server).
- Not done: a color picker for `color` and the firework colors, the sounds and particles registries (the language file does not name them; a server could), registry values straight from
  the server instead of the assets, and the potion colors without a server are those of the effects as recalled for this version.

### 13. Editors made for a kind of value (banner, firework, color; done in a browser, not live on a server)

- `src/editors.js`: the form asks `editorFor(field)` before it draws its generic control, so a kind of value can have a control of its own: the layers of a banner (dye colors, a gallery of the
  patterns drawn as banners, order), the bursts of a firework, a color. The firework star is tinted by its first burst (`fireworkColor`, the `firework` tint of the item definition).
- `src/listeditors.js` (with the words in `effects.js`): the effects of a potion (seconds, level in numerals, the effect from the game's list) and the modifiers of attributes (what the operation does to the amount, in words). A box typed in changes the
  store without a redraw, so a control reads the list as it is now (`store.get`) rather than the one it was drawn from; the firework's power box had the same fault and was fixed.
- The pages of a book (`Book.pages` and the `book` action's) are a box beside the page as it is drawn. Add and remove read the list as it is now in the editors of this step, so a typed box is never undone.
- Actions and conditions are said as sentences (`src/sentences.js`) under the type and in a folded row; `sentences.test.js` fails for an action or condition of the schema that has none. Only read, not yet edited as a sentence.
- `src/itemeditors.js`: `food` (the bar of drumsticks), `tool` (rules with chips and the usual tags), `fish` (pattern and dyes; `src/dyes.js` holds the dyes the banner uses too).
- Armor trims drawn as the game does (`trim_material` in `itemdef.js`, `paletted_permutations` of the item atlas in `assets.js` with `recolor`), which a resource pack changes as it does in the game; `trimEditor` shows the piece with each material. Checked in a browser against the game's files and a pack with another palette.
- Not done: the armor drawn on a player (the pattern only shows there), (the fish in its bucket is not drawn by the game either: the icon is one texture),
  the layout and the legend as a picture, and editing an action or condition by its sentence (the words are only read now).

### 14. A checked schema (done; unit tests, the editor in a browser, and the plugin on a live Folia server)

- `ConfigChecker` (plugin, `format/`) walks a menu file along the descriptors of the model, which is also where the editor's `schema.json` comes from, so the two cannot drift: a key that nothing reads
  (a warning, with the key it was probably meant to be), a missing key, a value of the wrong kind, an unknown type of an action, condition, catalog or activator, and a word that is not one of an enum's
  are told with their path and line, all at once (`MenuFormatException`). The warnings reach `/xmenu validate`, `/xmenu reload`, the log and the editor's save answer through `problemsOf`.
- The easy forms of a file are written the one way before it is read: one value for a list, an enum in any case. A number or switch as text was already read.
- Checked live (Folia, 2026-11-20): a menu with a mistyped action, a `slott`, a `max_stack_size`, `amount = 0`, `size = 100`, a `chance` of 150 and an amplifier of 300 is refused with its two errors **and** its five warnings, each with path and line, and the shipped menus load as before. The warnings of a refused file are in its message (`MenuFormatException.warnings`), and `ItemFactory` no longer says again what the file check has said about a fixed number.
- `@Within(min, max)` on the model gives a number its limits; they are in `schema.json` (`min`, `max`), in the editor's boxes and problems, and in the plugin's check.
- The editor: the same words and suggestions (`suggest.js`), a **Rename to …** button for a mistyped key, `normalize.js` so that the rest of the editor only sees the one way.
- Tests: `ConfigCheck` (and every menu that ships with the plugin or the live tests, except `i.conf` which has wrong items on purpose, reads with no warning), `SchemaTest`, `check.test.js`, `suggest.test.js`, `normalize.test.js`.
- The numbers of actions, conditions, activators and catalogs have `@Within` limits as well (`percent`, `amplifier`, `count`, `ticks`...); what the class refused when it was made is a strict limit, so it is told with its path and line.
- Not done: limits on numbers that are not whole (`pitch`, `progress`, `distance`, which `@Within` cannot give), and on the text ones (`amount` of `money`, `level`: they are placeholders); a key that was renamed or removed told as such (nothing has been);
  the same checks for the plugin's `config.yml`.
- A reference page of every key, `REFERENCE.md`, is written from the schema by `editor/tools/reference.mjs`; `reference.test.js` fails when it is stale.

### 15. Typed JSON (done; unit tests, and every route of the editor's endpoint called on a live server)

- The editor's endpoint and the relay no longer pass maps and read them back with casts: a request is read into a `@Serializable` class and a reply is written from one (`editor/Wire.kt`), so a request of the wrong shape is a `400` and not a `null` found further on. The menu itself still travels as a tree and is turned into the config object the plugin reads where it is read. The old `Json` helper (the config library used as a JSON parser) is gone, and Mojang's version manifest and asset index are read into classes too.
- `schema.json` is written from `SchemaFile` (a sealed `FieldType` with `kind` as the discriminator) and read back into it by `SchemaTest`, which no longer walks `jsonObject["..."]!!`; the file did not change a byte.
- The editor's requests to other servers (the relay, Mojang, the skin server) go through one client, `editor/Http.kt`, instead of a client in each: one connect timeout, a redirect followed only when asked (a skin is only fetched from its own host), and an answer read only up to its limit instead of whole. Checked live: `game-file` downloaded Unifont from Mojang through a redirect, and `bot/relay.js` ran the relay's session, polling and encrypted answers end to end.
- Not typed, on purpose: `ConfigChecker` walks the file along the descriptors itself, because it has to say every problem with its path and line, which decoding stops at the first of; `HoconWriter` writes the file as a person writes it.
- Live: `/v1/info`, `menus`, `check`, `colors`, `items`, `catalog`, `glyphs` and `elements` answered as before, a request of the wrong shape was refused with a message, and `live/setup.sh` now turns off the Velocity forwarding of the dev server it copies.
